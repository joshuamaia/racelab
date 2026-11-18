package dev.racelab.infrastructure.persistence;

import dev.racelab.domain.model.Category;
import dev.racelab.domain.model.ExecutionMode;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.ExperimentRecord;
import dev.racelab.domain.model.ExperimentResult;
import dev.racelab.domain.model.ExperimentStatus;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.model.Violation;
import dev.racelab.domain.port.ExperimentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcExperimentRepository implements ExperimentRepository {

    /** Every business table is namespaced by experiment_id; deleting an experiment removes its data too. */
    private static final List<String> NAMESPACED_TABLES = List.of(
            "account_movement", "purchase_order_item", "purchase_order", "reservation", "reservation_unsafe",
            "payment", "payment_unsafe", "idempotency_record", "side_effect", "processed_event", "outbox_event",
            "work_item", "doctor", "seat", "product", "account");

    private static final String SELECT = """
            SELECT e.*, c.configuration FROM experiment e
            LEFT JOIN experiment_configuration c ON c.experiment_id = e.id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public JdbcExperimentRepository(JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
    }

    @Override
    public void insert(ExperimentRecord r) {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                            INSERT INTO experiment (id, name, preset_id, scenario_id, category, strategy, status, execution_mode,
                                instance_id, comparison_id, warmup, rerun_of, correlation_id, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, r.id(), r.name(), r.presetId(), r.scenarioId(), r.category().name(), r.strategy().name(),
                    r.status().name(), r.executionMode().name(), r.instanceId(), r.comparisonId(), r.warmup(), r.rerunOf(),
                    r.correlationId(), Timestamp.from(r.createdAt()));
            jdbc.update("INSERT INTO experiment_configuration (experiment_id, configuration) VALUES (?, ?::jsonb)",
                    r.id(), json.writeValueAsString(r.configuration()));
        });
    }

    @Override
    public Optional<ExperimentRecord> findById(String id) {
        return jdbc.query(SELECT + " WHERE e.id = ?", mapper(), id).stream().findFirst();
    }

    @Override
    public PageResult<ExperimentRecord> search(SearchFilter f, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!f.includeChildren()) {
            where.append(" AND e.comparison_id IS NULL");
        }
        if (notBlank(f.category())) {
            where.append(" AND e.category = ?");
            args.add(f.category());
        }
        if (notBlank(f.status())) {
            where.append(" AND e.status = ?");
            args.add(f.status());
        }
        if (notBlank(f.scenarioId())) {
            where.append(" AND e.scenario_id = ?");
            args.add(f.scenarioId());
        }
        if (notBlank(f.strategy())) {
            where.append(" AND e.strategy = ?");
            args.add(f.strategy());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM experiment e" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<ExperimentRecord> content = jdbc.query(SELECT + where + " ORDER BY e.created_at DESC LIMIT ? OFFSET ?",
                mapper(), pageArgs.toArray());
        long t = total == null ? 0 : total;
        return new PageResult<>(content, page, size, t, (int) Math.ceil(t / (double) size));
    }

    @Override
    public List<ExperimentRecord> findByComparison(String comparisonId) {
        return jdbc.query(SELECT + " WHERE e.comparison_id = ? ORDER BY e.created_at", mapper(), comparisonId);
    }

    @Override
    public void markStarted(String id, Instant startedAt) {
        jdbc.update("UPDATE experiment SET status = 'RUNNING', started_at = ? WHERE id = ?", Timestamp.from(startedAt), id);
    }

    @Override
    public void markFinished(String id, ExperimentStatus status, Instant finishedAt, long durationMs, Boolean consistent,
                             int violationCount, int eventCount, String errorMessage) {
        jdbc.update("""
                        UPDATE experiment SET status = ?, finished_at = ?, duration_ms = ?, consistent = ?,
                            violation_count = ?, event_count = ?, error_message = ?
                        WHERE id = ?
                        """, status.name(), Timestamp.from(finishedAt), durationMs, consistent, violationCount, eventCount,
                errorMessage, id);
    }

    @Override
    public void saveResult(String id, ExperimentResult result) {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO experiment_result (experiment_id, result) VALUES (?, ?::jsonb)
                    ON CONFLICT (experiment_id) DO UPDATE SET result = excluded.result
                    """, id, json.writeValueAsString(result));
            jdbc.update("DELETE FROM experiment_metric WHERE experiment_id = ?", id);
            Map<String, Number> metrics = result.metrics().asMap();
            jdbc.batchUpdate("INSERT INTO experiment_metric (experiment_id, name, value) VALUES (?, ?, ?)",
                    new ArrayList<>(metrics.entrySet()), metrics.size(), (ps, e) -> {
                        ps.setString(1, id);
                        ps.setString(2, e.getKey());
                        ps.setBigDecimal(3, new java.math.BigDecimal(e.getValue().toString()));
                    });
            jdbc.update("DELETE FROM experiment_violation WHERE experiment_id = ?", id);
            for (Violation v : result.violations()) {
                jdbc.update("""
                                INSERT INTO experiment_violation (experiment_id, kind, rule, severity, expected, actual, difference, description)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """, id, v.kind().name(), truncate(v.rule(), 255), v.severity().name(), truncate(v.expected(), 128),
                        truncate(v.actual(), 128), truncate(v.difference(), 128), v.description());
            }
        });
    }

    @Override
    public Optional<ExperimentResult> findResult(String id) {
        return jdbc.query("SELECT result FROM experiment_result WHERE experiment_id = ?",
                        (rs, i) -> json.readValue(rs.getString("result"), ExperimentResult.class), id)
                .stream().findFirst();
    }

    @Override
    public void deleteWithData(String id) {
        tx.executeWithoutResult(s -> {
            for (String table : NAMESPACED_TABLES) {
                jdbc.update("DELETE FROM " + table + " WHERE experiment_id = ?", id);
            }
            jdbc.update("DELETE FROM experiment WHERE id = ?", id);
        });
    }

    @Override
    public int failInterrupted(String instanceId, String reason) {
        return jdbc.update("""
                UPDATE experiment SET status = 'FAILED', error_message = ?, finished_at = now()
                WHERE instance_id = ? AND status = 'RUNNING'
                """, reason, instanceId);
    }

    private RowMapper<ExperimentRecord> mapper() {
        return (rs, i) -> {
            String cfg = rs.getString("configuration");
            Boolean consistent = (Boolean) rs.getObject("consistent");
            long duration = rs.getLong("duration_ms");
            Long durationMs = rs.wasNull() ? null : duration;
            return new ExperimentRecord(rs.getString("id"), rs.getString("name"), rs.getString("preset_id"),
                    rs.getString("scenario_id"), Category.valueOf(rs.getString("category")),
                    StrategyType.valueOf(rs.getString("strategy")), ExperimentStatus.valueOf(rs.getString("status")),
                    ExecutionMode.valueOf(rs.getString("execution_mode")), rs.getString("instance_id"),
                    rs.getString("comparison_id"), rs.getBoolean("warmup"), rs.getString("rerun_of"),
                    rs.getString("correlation_id"), instant(rs.getTimestamp("created_at")),
                    instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")), durationMs,
                    consistent, rs.getInt("violation_count"), rs.getInt("event_count"), rs.getString("error_message"),
                    cfg == null ? null : json.readValue(cfg, ExperimentConfiguration.class));
        };
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}

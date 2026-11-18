package dev.racelab.infrastructure.persistence;

import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.port.EventStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persists events asynchronously from a dedicated writer thread using its own pooled connections.
 * Because the writes never join a worker's transaction, events of rolled-back transactions survive.
 */
@Component
public class JdbcEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcEventStore.class);
    private static final String INSERT = """
            INSERT INTO experiment_event (experiment_id, sequence, transaction_id, worker_id, thread_name, instance_id,
                correlation_id, connection_pid, event_type, resource, occurred_at, elapsed_ms, source, message, details)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (experiment_id, sequence) DO NOTHING
            """;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BlockingQueue<ExperimentEvent> queue = new LinkedBlockingQueue<>(500_000);
    private final Map<String, AtomicLong> pending = new ConcurrentHashMap<>();
    private final Thread writer;
    private volatile boolean running = true;

    public JdbcEventStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.writer = Thread.ofPlatform().daemon().name("racelab-event-writer").start(this::loop);
    }

    @Override
    public void append(ExperimentEvent event) {
        pending.computeIfAbsent(event.experimentId(), k -> new AtomicLong()).incrementAndGet();
        if (!queue.offer(event)) {
            write(List.of(event));
        }
    }

    @Override
    public void flush(String experimentId) {
        AtomicLong counter = pending.get(experimentId);
        long deadline = System.currentTimeMillis() + 15_000;
        while (counter != null && counter.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void loop() {
        List<ExperimentEvent> batch = new ArrayList<>(500);
        while (running || !queue.isEmpty()) {
            try {
                ExperimentEvent first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, 499);
                write(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            } finally {
                batch.clear();
            }
        }
    }

    private void write(List<ExperimentEvent> batch) {
        try {
            jdbc.batchUpdate(INSERT, batch, batch.size(), (ps, e) -> {
                ps.setString(1, e.experimentId());
                ps.setLong(2, e.sequence());
                ps.setString(3, e.transactionId());
                ps.setString(4, e.workerId());
                ps.setString(5, e.threadName());
                ps.setString(6, e.instanceId());
                ps.setString(7, e.correlationId());
                if (e.connectionPid() == null) {
                    ps.setNull(8, Types.INTEGER);
                } else {
                    ps.setInt(8, e.connectionPid().intValue());
                }
                ps.setString(9, e.eventType().name());
                ps.setString(10, e.resource());
                ps.setTimestamp(11, Timestamp.from(e.timestamp()));
                ps.setLong(12, e.elapsedTimeMs());
                ps.setString(13, e.source().name());
                ps.setString(14, e.message());
                ps.setString(15, json.writeValueAsString(e.details() == null ? Map.of() : e.details()));
            });
        } catch (RuntimeException ex) {
            // e.g. experiment deleted while events were in flight (FK) — never break the writer loop
            log.warn("Could not persist {} event(s): {}", batch.size(), ex.getMessage());
        } finally {
            for (ExperimentEvent e : batch) {
                AtomicLong c = pending.get(e.experimentId());
                if (c != null && c.decrementAndGet() <= 0) {
                    pending.remove(e.experimentId(), c);
                }
            }
        }
    }

    @Override
    public List<ExperimentEvent> findAfter(String experimentId, long afterSequence, int limit) {
        return jdbc.query("SELECT * FROM experiment_event WHERE experiment_id = ? AND sequence > ? ORDER BY sequence LIMIT ?",
                mapper(), experimentId, afterSequence, limit);
    }

    @Override
    public List<ExperimentEvent> findAll(String experimentId) {
        return jdbc.query("SELECT * FROM experiment_event WHERE experiment_id = ? ORDER BY sequence", mapper(), experimentId);
    }

    private RowMapper<ExperimentEvent> mapper() {
        return (rs, i) -> {
            int pid = rs.getInt("connection_pid");
            Long connectionPid = rs.wasNull() ? null : (long) pid;
            String details = rs.getString("details");
            return new ExperimentEvent(rs.getString("experiment_id"), rs.getLong("sequence"),
                    rs.getString("transaction_id"), rs.getString("worker_id"), rs.getString("thread_name"),
                    rs.getString("instance_id"), rs.getString("correlation_id"), connectionPid,
                    EventType.valueOf(rs.getString("event_type")), rs.getString("resource"),
                    rs.getTimestamp("occurred_at").toInstant(), rs.getLong("elapsed_ms"),
                    EventSource.valueOf(rs.getString("source")), rs.getString("message"),
                    details == null ? Map.of() : json.readValue(details, MAP));
        };
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        writer.join(5_000);
    }
}

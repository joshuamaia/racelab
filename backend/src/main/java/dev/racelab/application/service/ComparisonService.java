package dev.racelab.application.service;

import dev.racelab.application.engine.ExperimentExecutor;
import dev.racelab.application.engine.ExperimentRegistry;
import dev.racelab.application.exception.ApiException;
import dev.racelab.domain.model.ExecutionMode;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.ExperimentResult;
import dev.racelab.domain.model.ExperimentStatus;
import dev.racelab.domain.model.ScenarioDescriptor;
import dev.racelab.domain.model.StateRow;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.port.ExperimentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Strategy Comparator: runs the same scenario with several strategies. Every run is a full, independent
 * experiment with its own id and its own freshly seeded namespace, so no strategy ever starts from the
 * final state of another one. Numbers are averages of the measured (non warm-up) runs.
 */
@Service
public class ComparisonService {

    private static final Logger log = LoggerFactory.getLogger(ComparisonService.class);
    public static final String DISCLAIMER = "Results depend on hardware, database configuration, load and the scenario. "
            + "They describe this run on this machine — no strategy is universally better: each trades consistency, "
            + "latency, throughput and complexity differently.";

    private final ExperimentService experiments;
    private final ExperimentExecutor executor;
    private final ExperimentRegistry registry;
    private final ExperimentRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final ExecutorService runner = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("racelab-comparison-", 1).factory());
    private final Map<String, Boolean> cancelled = new ConcurrentHashMap<>();

    public ComparisonService(ExperimentService experiments, ExperimentExecutor executor, ExperimentRegistry registry,
                             ExperimentRepository repository, JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.experiments = experiments;
        this.executor = executor;
        this.registry = registry;
        this.repository = repository;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public record CreateComparison(String name, String scenarioId, List<StrategyType> strategies,
                                   ExperimentConfiguration configuration, Integer repetitions, Integer warmupRuns) {
    }

    public record Progress(int completedRuns, int totalRuns, StrategyType currentStrategy) {
    }

    public record StrategyResult(StrategyType strategy, List<String> experimentIds, int measuredRuns,
                                 Map<String, Double> metrics, int consistentRuns, int inconsistentRuns,
                                 boolean consistencyPreserved, List<StateRow> finalStateRows) {
    }

    public record Comparison(String id, String name, String scenarioId, String status, List<StrategyType> strategies,
                             ExperimentConfiguration configuration, int repetitions, int warmupRuns, Progress progress,
                             Instant createdAt, Instant finishedAt, List<StrategyResult> results, String errorMessage,
                             String disclaimer) {
    }

    public Comparison create(CreateComparison req) {
        ScenarioDescriptor scenario = registry.get(req.scenarioId()).descriptor();
        List<StrategyType> strategies = req.strategies() == null ? List.of() : List.copyOf(new LinkedHashSet<>(req.strategies()));
        if (strategies.size() < 2 || strategies.size() > 8) {
            throw ApiException.validation("Select between 2 and 8 strategies");
        }
        for (StrategyType s : strategies) {
            if (!scenario.supports(s)) {
                throw new ApiException(400, "UNSUPPORTED_STRATEGY", "Scenario " + scenario.id() + " does not support " + s);
            }
        }
        int reps = req.repetitions() == null ? 1 : req.repetitions();
        int warmup = req.warmupRuns() == null ? 0 : req.warmupRuns();
        if (reps < 1 || reps > 10 || warmup < 0 || warmup > 3) {
            throw ApiException.validation("repetitions must be 1..10 and warmupRuns 0..3");
        }
        ExperimentConfiguration cfg = (req.configuration() == null ? ExperimentConfiguration.empty() : req.configuration())
                .withDefaults(scenario.defaults()).withExecutionMode(ExecutionMode.CONCURRENT);
        String id = ExperimentService.newId("CMP");
        String name = req.name() == null || req.name().isBlank() ? scenario.name() + " — " + strategies.size() + " strategies" : req.name();
        int total = strategies.size() * (reps + warmup);
        jdbc.update("""
                INSERT INTO comparison (id, name, scenario_id, status, strategies, configuration, repetitions, warmup_runs, progress, created_at)
                VALUES (?, ?, ?, 'RUNNING', ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?)
                """, id, name, scenario.id(), json.writeValueAsString(strategies), json.writeValueAsString(cfg), reps, warmup,
                json.writeValueAsString(new Progress(0, total, strategies.getFirst())), Timestamp.from(clock.instant()));
        runner.submit(() -> run(id, scenario.id(), strategies, cfg, reps, warmup));
        return get(id);
    }

    private void run(String id, String scenarioId, List<StrategyType> strategies, ExperimentConfiguration cfg, int reps, int warmup) {
        int total = strategies.size() * (reps + warmup);
        int done = 0;
        String status = "COMPLETED";
        String error = null;
        try {
            for (StrategyType strategy : strategies) {
                for (int run = 0; run < warmup + reps; run++) {
                    if (cancelled.containsKey(id)) {
                        throw new IllegalStateException("cancelled");
                    }
                    boolean isWarmup = run < warmup;
                    ExperimentView v = experiments.create(new ExperimentService.CreateCommand(null, scenarioId, strategy,
                            "Comparison " + id + " · " + strategy + (isWarmup ? " · warm-up" : " · run " + (run - warmup + 1)),
                            cfg, false, null, id, isWarmup, null));
                    executor.runInline(experiments.record(v.id()), Duration.ofMinutes(5));
                    done++;
                    jdbc.update("UPDATE comparison SET progress = ?::jsonb WHERE id = ?",
                            json.writeValueAsString(new Progress(done, total, strategy)), id);
                }
            }
        } catch (Exception e) {
            status = cancelled.containsKey(id) ? "CANCELLED" : "FAILED";
            error = cancelled.containsKey(id) ? "Cancelled by the user" : e.getMessage();
            if (!cancelled.containsKey(id)) {
                log.error("Comparison {} failed", id, e);
            }
        } finally {
            cancelled.remove(id);
        }
        List<StrategyResult> results = aggregate(id, strategies);
        jdbc.update("UPDATE comparison SET status = ?, results = ?::jsonb, error_message = ?, finished_at = ? WHERE id = ?",
                status, json.writeValueAsString(results), error, Timestamp.from(clock.instant()), id);
    }

    private List<StrategyResult> aggregate(String id, List<StrategyType> strategies) {
        List<StrategyResult> out = new ArrayList<>();
        var runs = repository.findByComparison(id);
        for (StrategyType s : strategies) {
            List<String> ids = new ArrayList<>();
            List<ExperimentResult> measured = new ArrayList<>();
            for (var r : runs) {
                if (r.strategy() != s || r.warmup() || r.status() != ExperimentStatus.COMPLETED) {
                    continue;
                }
                ids.add(r.id());
                repository.findResult(r.id()).ifPresent(measured::add);
            }
            Map<String, Double> avg = new LinkedHashMap<>();
            for (ExperimentResult m : measured) {
                m.metrics().asMap().forEach((k, v) -> avg.merge(k, v.doubleValue(), Double::sum));
            }
            avg.replaceAll((k, v) -> Math.round(v / Math.max(1, measured.size()) * 100.0) / 100.0);
            int consistent = (int) measured.stream().filter(ExperimentResult::consistent).count();
            out.add(new StrategyResult(s, ids, measured.size(), avg, consistent, measured.size() - consistent,
                    !measured.isEmpty() && consistent == measured.size(),
                    measured.isEmpty() ? List.of() : measured.getLast().stateRows()));
        }
        return out;
    }

    public void cancel(String id) {
        Comparison c = get(id);
        if (!"RUNNING".equals(c.status())) {
            throw ApiException.invalidState("Comparison " + id + " is " + c.status());
        }
        cancelled.put(id, true);
        repository.findByComparison(id).stream().filter(r -> r.status() == ExperimentStatus.RUNNING)
                .forEach(r -> executor.context(r.id()).ifPresent(ctx -> executor.cancel(r.id(), "Comparison cancelled")));
    }

    public Comparison get(String id) {
        return find(id).orElseThrow(() -> ApiException.notFound("Comparison " + id));
    }

    public List<Comparison> list() {
        return jdbc.query("SELECT * FROM comparison ORDER BY created_at DESC LIMIT 50", (rs, i) -> map(rs));
    }

    private Optional<Comparison> find(String id) {
        return jdbc.query("SELECT * FROM comparison WHERE id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    private Comparison map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String results = rs.getString("results");
        String progress = rs.getString("progress");
        Timestamp finished = rs.getTimestamp("finished_at");
        return new Comparison(rs.getString("id"), rs.getString("name"), rs.getString("scenario_id"), rs.getString("status"),
                json.readValue(rs.getString("strategies"), new TypeReference<List<StrategyType>>() {
                }),
                json.readValue(rs.getString("configuration"), ExperimentConfiguration.class),
                rs.getInt("repetitions"), rs.getInt("warmup_runs"),
                progress == null ? null : json.readValue(progress, Progress.class),
                rs.getTimestamp("created_at").toInstant(), finished == null ? null : finished.toInstant(),
                results == null ? List.of() : json.readValue(results, new TypeReference<List<StrategyResult>>() {
                }),
                rs.getString("error_message"), DISCLAIMER);
    }
}

package dev.racelab.application.engine;

import dev.racelab.application.exception.ApiException;
import dev.racelab.application.recommendation.RecommendationEngine;
import dev.racelab.application.service.EnvironmentInfo;
import dev.racelab.domain.model.EventDraft;
import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.model.ExperimentRecord;
import dev.racelab.domain.model.ExperimentResult;
import dev.racelab.domain.model.ExperimentStatus;
import dev.racelab.domain.model.LockGraph;
import dev.racelab.domain.model.Metrics;
import dev.racelab.domain.model.Recommendation;
import dev.racelab.domain.model.ScenarioDescriptor;
import dev.racelab.domain.model.StepState;
import dev.racelab.domain.model.Topology;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.Violation;
import dev.racelab.domain.port.EventStore;
import dev.racelab.domain.port.ExperimentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Runs experiments: admission control (max concurrent experiments), lifecycle events, result assembly
 * (metrics, lock graph, recommendations), persistence and Micrometer metrics.
 */
@Component
public class ExperimentExecutor {

    private static final Logger log = LoggerFactory.getLogger(ExperimentExecutor.class);

    private final ExperimentRegistry registry;
    private final ExperimentRepository repository;
    private final EventStore eventStore;
    private final EngineServices services;
    private final ExperimentMetricsCollector metricsCollector;
    private final RecommendationEngine recommendations;
    private final EnvironmentInfo environment;
    private final MeterRegistry meters;
    private final Semaphore slots;
    private final int maxConcurrent;
    private final ExecutorService orchestrators = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().name("racelab-orchestrator-", 1).factory());
    private final Map<String, ExperimentContext> running = new ConcurrentHashMap<>();

    public ExperimentExecutor(ExperimentRegistry registry, ExperimentRepository repository, EventStore eventStore,
                              EngineServices services, ExperimentMetricsCollector metricsCollector,
                              RecommendationEngine recommendations, EnvironmentInfo environment, MeterRegistry meters,
                              @Value("${racelab.limits.max-concurrent-experiments:3}") int maxConcurrent) {
        this.registry = registry;
        this.repository = repository;
        this.eventStore = eventStore;
        this.services = services;
        this.metricsCollector = metricsCollector;
        this.recommendations = recommendations;
        this.environment = environment;
        this.meters = meters;
        this.maxConcurrent = maxConcurrent;
        this.slots = new Semaphore(maxConcurrent, true);
        meters.gauge("racelab.experiments.active", running, Map::size);
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverInterrupted() {
        int n = repository.failInterrupted(services.instanceId(), "Interrupted: the backend restarted while it was running");
        if (n > 0) {
            log.warn("Marked {} interrupted experiment(s) as FAILED", n);
        }
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public int activeCount() {
        return running.size();
    }

    public Optional<ExperimentContext> context(String id) {
        return Optional.ofNullable(running.get(id));
    }

    /** Persists a new experiment and publishes EXPERIMENT_CREATED. */
    public ExperimentRecord create(ExperimentRecord record) {
        repository.insert(record);
        LiveEventBus bus = services.publisher().open(record.id(), record.correlationId());
        bus.publish(EventDraft.experiment(EventType.EXPERIMENT_CREATED, "Experiment created: " + record.name(),
                Map.of("scenarioId", record.scenarioId(), "strategy", record.strategy().name(),
                        "executionMode", record.executionMode().name())));
        return record;
    }

    /** Validates and starts asynchronously. */
    public void start(ExperimentRecord record) {
        validateRunnable(record);
        if (!slots.tryAcquire()) {
            throw new ApiException(409, "TOO_MANY_EXPERIMENTS",
                    "At most " + maxConcurrent + " experiments can run at the same time on this instance");
        }
        ExperimentContext ctx;
        try {
            ctx = prepare(record);
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
        orchestrators.submit(() -> {
            try {
                run(ctx);
            } finally {
                slots.release();
            }
        });
    }

    /** Runs synchronously on the calling thread (strategy comparisons), waiting for a free slot. */
    public ExperimentStatus runInline(ExperimentRecord record, Duration maxWait) throws InterruptedException {
        validateRunnable(record);
        if (!slots.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new ApiException(409, "TOO_MANY_EXPERIMENTS", "No free experiment slot");
        }
        try {
            return run(prepare(record));
        } finally {
            slots.release();
        }
    }

    private void validateRunnable(ExperimentRecord record) {
        if (record.status() != ExperimentStatus.CREATED) {
            throw ApiException.invalidState("Experiment " + record.id() + " is " + record.status());
        }
        ScenarioDescriptor d = registry.get(record.scenarioId()).descriptor();
        if (!d.supports(record.strategy())) {
            throw new ApiException(400, "UNSUPPORTED_STRATEGY",
                    "Scenario " + d.id() + " does not support strategy " + record.strategy());
        }
        if (record.configuration().topology() == Topology.DISTRIBUTED) {
            if (!d.supportsDistributed()) {
                throw ApiException.validation("Scenario " + d.id() + " does not support the DISTRIBUTED topology");
            }
            if (!services.remoteWorkers().isPeerAvailable()) {
                throw new ApiException(503, "PEER_UNAVAILABLE",
                        "The secondary backend instance is not reachable. Start the stack with "
                                + "'docker compose --profile distributed up -d'.");
            }
        }
        if (record.strategy().requiresRedis() || d.id().equals("lock-expiration")) {
            if (!services.distributedLocks().isAvailable()) {
                throw new ApiException(503, "REDIS_UNAVAILABLE", "Redis is not reachable; Redis-based strategies are unavailable.");
            }
        }
        if (record.configuration().stepByStep() && !d.supportsStepMode()) {
            throw ApiException.validation("Scenario " + d.id() + " does not support step-by-step execution");
        }
    }

    private ExperimentContext prepare(ExperimentRecord record) {
        long lastSequence = services.publisher().bus(record.id()).map(LiveEventBus::lastSequence)
                .orElseGet(() -> eventStore.findAll(record.id()).stream().mapToLong(ExperimentEvent::sequence).max().orElse(0));
        LiveEventBus bus = services.publisher().reopen(record.id(), record.correlationId(), lastSequence);
        ExperimentContext ctx = new ExperimentContext(record, services, bus);
        running.put(record.id(), ctx);
        return ctx;
    }

    private ExperimentStatus run(ExperimentContext ctx) {
        ExperimentRecord record = ctx.record();
        MDC.put("experimentId", record.id());
        MDC.put("correlationId", record.correlationId() == null ? "" : record.correlationId());
        Instant started = services.clock().instant();
        long t0 = System.nanoTime();
        ExperimentStatus status = ExperimentStatus.FAILED;
        String error = null;
        ExperimentResult result = null;
        try {
            repository.markStarted(record.id(), started);
            ctx.event(EventType.EXPERIMENT_STARTED, "Experiment started (" + record.strategy() + ", "
                    + record.configuration().threads() + " workers, " + record.configuration().transactions()
                    + " transactions, " + record.configuration().isolationLevel() + ")", Map.of(
                    "threads", record.configuration().threads(), "transactions", record.configuration().transactions(),
                    "isolationLevel", record.configuration().isolationLevel().name(),
                    "executionMode", record.configuration().executionMode().name(),
                    "topology", record.configuration().topology().name()));
            Experiment experiment = registry.get(record.scenarioId());
            ScenarioReport report = experiment.execute(record.configuration(), ctx);
            long totalMs = (System.nanoTime() - t0) / 1_000_000;
            status = ctx.token().isCancelled() ? ExperimentStatus.CANCELLED : ExperimentStatus.COMPLETED;
            if (status == ExperimentStatus.CANCELLED) {
                error = ctx.token().reason();
            }
            result = assemble(ctx, report, status, totalMs);
        } catch (ScenarioExceptions.ExperimentCancelledException e) {
            status = ExperimentStatus.CANCELLED;
            error = ctx.token().reason() != null ? ctx.token().reason() : e.getMessage();
        } catch (Exception e) {
            if (ctx.token().isCancelled()) {
                status = ExperimentStatus.CANCELLED;
                error = ctx.token().reason();
            } else {
                status = ExperimentStatus.FAILED;
                error = SqlErrorClassifier.rootMessage(e);
                log.error("Experiment {} failed", record.id(), e);
            }
        } finally {
            ctx.close();
        }
        long durationMs = (System.nanoTime() - t0) / 1_000_000;
        try {
            finish(ctx, record, status, result, durationMs, error);
        } finally {
            running.remove(record.id());
            MDC.remove("experimentId");
            MDC.remove("correlationId");
        }
        return status;
    }

    private ExperimentResult assemble(ExperimentContext ctx, ScenarioReport report, ExperimentStatus status, long totalMs) {
        List<TransactionOutcome> outcomes = ctx.outcomes();
        for (Violation v : report.violations()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("rule", v.rule());
            d.put("expected", v.expected());
            d.put("actual", v.actual());
            d.put("difference", v.difference());
            d.put("severity", v.severity().name());
            d.put("transactions", v.transactions());
            ctx.publish(new EventDraft(null, null, Thread.currentThread().getName(), null, null,
                    EventType.INCONSISTENCY_DETECTED, null, EventSource.INSTRUMENTED,
                    "Invariant violated: " + v.rule() + " (expected " + v.expected() + ", found " + v.actual() + ")", d));
        }
        List<ExperimentEvent> events = ctx.bus().snapshot();
        Metrics metrics = metricsCollector.collect(outcomes, events, totalMs, report.violations().size());
        LockGraph graph = ctx.deadlocks().isEmpty()
                ? LockGraphBuilder.fromObservedWaits(events, ctx::workerOfPid)
                : LockGraphBuilder.fromDeadlock(ctx.deadlocks().getFirst(), ctx::workerOfPid);
        List<Recommendation> recs = recommendations.recommend(new RecommendationEngine.Input(ctx.record().scenarioId(),
                ctx.strategy(), ctx.config(), report.violations(), metrics, report.observations(), outcomes));
        boolean consistent = report.violations().isEmpty();
        return new ExperimentResult(ctx.id(), status, consistent, report.summary(), report.initialState(),
                report.finalState(), report.stateRows(), outcomes, report.violations(), report.observations(), metrics,
                recs, graph, environment.snapshot());
    }

    private void finish(ExperimentContext ctx, ExperimentRecord record, ExperimentStatus status, ExperimentResult result,
                        long durationMs, String error) {
        EventType terminal = switch (status) {
            case COMPLETED -> EventType.EXPERIMENT_COMPLETED;
            case CANCELLED -> EventType.EXPERIMENT_CANCELLED;
            default -> EventType.EXPERIMENT_FAILED;
        };
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", status.name());
        details.put("durationMs", durationMs);
        if (result != null) {
            details.put("consistent", result.consistent());
            details.put("violations", result.violations().size());
            details.put("summary", result.summary());
        }
        if (error != null) {
            details.put("error", error);
        }
        ctx.event(terminal, result != null ? result.summary() : ("Experiment " + status.name().toLowerCase()
                + (error != null ? ": " + error : "")), details);
        try {
            eventStore.flush(record.id());
            if (result != null) {
                repository.saveResult(record.id(), result);
            }
            repository.markFinished(record.id(), status, services.clock().instant(), durationMs,
                    result == null ? null : result.consistent(), result == null ? 0 : result.violations().size(),
                    (int) ctx.bus().lastSequence(), error);
        } catch (RuntimeException e) {
            log.error("Could not persist the outcome of experiment {}", record.id(), e);
        } finally {
            ctx.bus().close(status);
        }
        Counter.builder("racelab.experiments.finished")
                .tag("scenario", record.scenarioId()).tag("strategy", record.strategy().name())
                .tag("status", status.name())
                .tag("consistent", result == null ? "unknown" : String.valueOf(result.consistent()))
                .register(meters).increment();
        Timer.builder("racelab.experiment.duration").tag("scenario", record.scenarioId())
                .tag("strategy", record.strategy().name()).register(meters).record(Duration.ofMillis(durationMs));
    }

    // ------------------------------------------------------------------ live control

    public void cancel(String id, String reason) {
        if (running.containsKey(id)) {
            services.cancellations().cancel(id, reason);
        } else {
            throw ApiException.invalidState("Experiment " + id + " is not running on this instance");
        }
    }

    public String step(String id, String workerId) {
        ExperimentContext ctx = runningContext(id);
        try {
            return ctx.steps().release(workerId);
        } catch (IllegalStateException e) {
            throw ApiException.invalidState(e.getMessage());
        }
    }

    public void resume(String id) {
        ExperimentContext ctx = runningContext(id);
        ctx.steps().resume();
        ctx.event(EventType.CHECKPOINT_RELEASED, "Step-by-step mode left: execution continues freely", Map.of());
    }

    public Optional<StepState> stepState(String id) {
        ExperimentContext ctx = running.get(id);
        if (ctx == null || !ctx.config().stepByStep()) {
            return Optional.empty();
        }
        return Optional.of(ctx.stepState());
    }

    private ExperimentContext runningContext(String id) {
        ExperimentContext ctx = running.get(id);
        if (ctx == null) {
            throw ApiException.invalidState("Experiment " + id + " is not running on this instance");
        }
        return ctx;
    }

    public List<String> runningIds() {
        return new ArrayList<>(running.keySet());
    }

    @PreDestroy
    void shutdown() {
        running.keySet().forEach(id -> services.cancellations().cancel(id, "Backend shutting down"));
        orchestrators.shutdown();
        try {
            orchestrators.awaitTermination(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

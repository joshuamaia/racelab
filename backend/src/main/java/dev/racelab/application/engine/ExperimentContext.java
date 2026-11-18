package dev.racelab.application.engine;

import dev.racelab.application.engine.LockGraphBuilder.DeadlockReport;
import dev.racelab.application.port.RemoteWorkerGateway.RemoteWorkerRequest;
import dev.racelab.domain.model.EventDraft;
import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.ExperimentRecord;
import dev.racelab.domain.model.StepState;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.TxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runtime state of one experiment execution: identity, configuration, the event bus, cancellation, step
 * controller, rendezvous, lock-wait observation and the worker threads.
 */
public final class ExperimentContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExperimentContext.class);
    private static final Duration MAX_WORKER_PHASE = Duration.ofMinutes(10);

    private final ExperimentRecord record;
    private final ExperimentConfiguration config;
    private final EngineServices services;
    private final LiveEventBus bus;
    private final CancellationToken token;
    private final StepController steps;
    private final BlockRegistry blocks;
    private final LockWaitObserver observer;
    private final Map<Long, String> workerOfPid = new ConcurrentHashMap<>();
    private final List<DeadlockReport> deadlocks = Collections.synchronizedList(new ArrayList<>());
    private final List<TransactionOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> shared = new ConcurrentHashMap<>();
    private volatile Rendezvous rendezvous = new Rendezvous(List.of(), w -> true);

    public ExperimentContext(ExperimentRecord record, EngineServices services, LiveEventBus bus) {
        this.record = record;
        this.config = record.configuration();
        this.services = services;
        this.bus = bus;
        this.token = services.cancellations().register(record.id());
        this.steps = new StepController(config.stepByStep(), Duration.ofSeconds(config.stepTimeoutSeconds()),
                services.clock(), token, reason -> services.cancellations().cancel(record.id(), reason));
        this.blocks = new BlockRegistry(() -> rendezvous.wake());
        this.observer = new LockWaitObserver(services.jdbc(), blocks, workerOfPid::get,
                (workerId, pid, type, source, message, details) -> publish(new EventDraft(null, workerId, null, null,
                        pid, type, null, source, message, details)));
        token.onCancel(() -> rendezvous.wake());
    }

    // ------------------------------------------------------------------ identity & collaborators

    public String id() {
        return record.id();
    }

    public ExperimentRecord record() {
        return record;
    }

    public ExperimentConfiguration config() {
        return config;
    }

    public StrategyType strategy() {
        return record.strategy();
    }

    public EngineServices services() {
        return services;
    }

    public JdbcTemplate jdbc() {
        return services.jdbc();
    }

    public TransactionCoordinator coordinator() {
        return services.coordinator();
    }

    public CancellationToken token() {
        return token;
    }

    public StepController steps() {
        return steps;
    }

    public BlockRegistry blocks() {
        return blocks;
    }

    public LiveEventBus bus() {
        return bus;
    }

    public Map<String, Object> shared() {
        return shared;
    }

    public List<DeadlockReport> deadlocks() {
        return List.copyOf(deadlocks);
    }

    public List<TransactionOutcome> outcomes() {
        return List.copyOf(outcomes);
    }

    public String workerOfPid(Long pid) {
        return pid == null ? null : workerOfPid.get(pid);
    }

    // ------------------------------------------------------------------ events

    public void publish(EventDraft draft) {
        bus.publish(draft);
    }

    public void event(EventType type, String message, Map<String, Object> details) {
        bus.publish(EventDraft.experiment(type, message, details));
    }

    /** Runs a short setup/snapshot transaction (seeding, initial and final state). */
    public <T> T setup(TransactionCallback<T> callback) {
        TransactionTemplate template = new TransactionTemplate(services.transactionManager());
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(30);
        return template.execute(callback);
    }

    // ------------------------------------------------------------------ workers

    public WorkerRuntime runtimeFor(String workerId, int index, String instanceId, String threadName) {
        return new LocalWorkerRuntime(this, workerId, index, instanceId, threadName);
    }

    /**
     * Runs the workers concurrently. Local workers are released together by a start gate
     * ({@link CountDownLatch}); critical sections are then coordinated by the {@link Rendezvous}.
     */
    public List<TransactionOutcome> runWorkers(List<WorkerSpec> specs) {
        rendezvous = new Rendezvous(specs.stream().map(WorkerSpec::workerId).toList(), blocks::isBlocked);
        observer.start("racelab-" + id() + "-lock-observer");
        long localCount = specs.stream().filter(s -> !s.remote()).count();
        CountDownLatch ready = new CountDownLatch((int) localCount);
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool((int) Math.max(1, localCount),
                Thread.ofPlatform().name("racelab-" + id() + "-pool-", 1).factory());
        ExecutorService remotePool = Executors.newVirtualThreadPerTaskExecutor();
        List<CompletableFuture<List<TransactionOutcome>>> futures = new ArrayList<>();
        try {
            for (WorkerSpec spec : specs) {
                if (spec.remote()) {
                    futures.add(CompletableFuture.supplyAsync(() -> runRemote(spec, startGate), remotePool));
                } else {
                    futures.add(CompletableFuture.supplyAsync(() -> runLocal(spec, ready, startGate), pool));
                }
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                log.warn("Not every worker of {} became ready in time", id());
            }
            startGate.countDown();
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
            try {
                all.get(MAX_WORKER_PHASE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                services.cancellations().cancel(id(), "Experiment exceeded " + MAX_WORKER_PHASE.toMinutes() + " minutes");
                all.get(60, TimeUnit.SECONDS);
            }
            List<TransactionOutcome> result = new ArrayList<>();
            for (CompletableFuture<List<TransactionOutcome>> f : futures) {
                result.addAll(f.join());
            }
            outcomes.addAll(result);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            services.cancellations().cancel(id(), "Interrupted");
            throw new ScenarioExceptions.ExperimentCancelledException("Interrupted");
        } catch (Exception e) {
            throw new IllegalStateException("Worker execution failed: " + e.getMessage(), e);
        } finally {
            startGate.countDown();
            pool.shutdown();
            remotePool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private List<TransactionOutcome> runLocal(WorkerSpec spec, CountDownLatch ready, CountDownLatch startGate) {
        Thread current = Thread.currentThread();
        String previousName = current.getName();
        current.setName("racelab-" + id() + "-" + spec.workerId());
        MDC.put("experimentId", id());
        MDC.put("workerId", spec.workerId());
        WorkerRuntime rt = runtimeFor(spec.workerId(), spec.index(), services.instanceId(), current.getName());
        try {
            ready.countDown();
            startGate.await();
            return spec.task().run(rt);
        } catch (Exception e) {
            boolean cancelled = token.isCancelled();
            if (!cancelled) {
                log.error("Worker {} of {} failed", spec.workerId(), id(), e);
            }
            return List.of(failure(spec.workerId(), services.instanceId(), cancelled, e));
        } finally {
            rendezvous.finished(spec.workerId());
            blocks.unblock(spec.workerId());
            MDC.remove("experimentId");
            MDC.remove("workerId");
            current.setName(previousName);
        }
    }

    private List<TransactionOutcome> runRemote(WorkerSpec spec, CountDownLatch startGate) {
        String peer = services.remoteWorkers().peerInstanceId();
        try {
            startGate.await();
            RemoteWorkerRequest request = new RemoteWorkerRequest(id(), record.scenarioId(), record.strategy(), config,
                    spec.workerId(), spec.index(), spec.operations(), services.selfUrl(), record.correlationId(),
                    spec.remoteData());
            return services.remoteWorkers().execute(request);
        } catch (Exception e) {
            boolean cancelled = token.isCancelled();
            log.error("Remote worker {} of {} failed", spec.workerId(), id(), e);
            return List.of(failure(spec.workerId(), peer, cancelled, e));
        } finally {
            rendezvous.finished(spec.workerId());
            blocks.unblock(spec.workerId());
        }
    }

    private static TransactionOutcome failure(String workerId, String instanceId, boolean cancelled, Exception e) {
        return new TransactionOutcome(workerId, null, List.of(), instanceId,
                cancelled ? TxStatus.CANCELLED : TxStatus.FAILED, 0, 0, 0, null, null,
                SqlErrorClassifier.rootMessage(e), cancelled ? "The experiment was cancelled." : "Worker failed unexpectedly.",
                cancelled, Map.of(), Map.of(), Map.of());
    }

    // ------------------------------------------------------------------ hooks used by worker runtimes

    void checkpoint(WorkerRuntime rt, String step, String description) {
        token.throwIfCancelled();
        boolean parked = steps.reach(rt.workerId(), step, description, () -> rt.emit(null, null,
                EventType.CHECKPOINT_REACHED, EventSource.INSTRUMENTED, null, "Waiting at checkpoint: " + description,
                Map.of("step", step)));
        if (parked) {
            rt.emit(null, null, EventType.CHECKPOINT_RELEASED, EventSource.INSTRUMENTED, null,
                    "Released: " + description, Map.of("step", step));
        }
    }

    void rendezvous(WorkerRuntime rt, String point) {
        if (steps.isActive() || !Boolean.TRUE.equals(config.coordinated())) {
            return;
        }
        boolean all = rendezvous.await(point, rt.workerId(), config.transactionTimeoutMs(), token::isCancelled);
        token.throwIfCancelled();
        if (!all) {
            rt.emit(null, null, EventType.LOCK_WAITING, EventSource.INSTRUMENTED, null,
                    "Coordination point '" + point + "' timed out; continuing without the other workers",
                    Map.of("point", point));
        }
    }

    void registerPid(String workerId, long pid) {
        workerOfPid.put(pid, workerId);
        services.cancellations().trackPid(id(), pid);
    }

    void unregisterPid(long pid) {
        services.cancellations().untrackPid(id(), pid);
    }

    void reportDeadlock(String workerId, long victimPid, String detail) {
        deadlocks.add(new DeadlockReport(victimPid, workerId, detail));
    }

    // ------------------------------------------------------------------ step mode

    public StepState stepState() {
        List<StepState.Blocked> blocked = blocks.all().stream()
                .filter(b -> b.kind() != BlockRegistry.Kind.DATABASE || b.confirmed())
                .map(b -> new StepState.Blocked(b.workerId(), describe(b)))
                .toList();
        return steps.snapshot(blocked);
    }

    private String describe(BlockRegistry.Block b) {
        if (b.kind() == BlockRegistry.Kind.DATABASE) {
            List<String> by = b.blockedBy().stream().map(p -> {
                String w = workerOfPid.get(p);
                return w == null ? "pid " + p : w + " (pid " + p + ")";
            }).toList();
            return "Waiting for a database lock (observed " + b.waitEvent() + ", blocked by " + String.join(", ", by) + ")";
        }
        return "Waiting for " + b.kind().name().toLowerCase() + " lock: " + b.reason();
    }

    @Override
    public void close() {
        observer.close();
        services.cancellations().unregister(id());
    }
}

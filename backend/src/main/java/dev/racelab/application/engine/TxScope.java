package dev.racelab.application.engine;

import dev.racelab.application.engine.ScenarioExceptions.BusinessRuleRejection;
import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One attempt of one transaction running on one worker thread, bound to its own connection by the Spring
 * transaction manager. Offers instrumented helpers for the phases of a transaction so every scenario
 * produces the same, comparable event vocabulary.
 */
public final class TxScope {

    private final WorkerRuntime rt;
    private final String txId;
    private final int attempt;
    private final TxSpec spec;
    private final JdbcTemplate jdbc;
    private Long pid;
    private long lockWaitMs;
    private final Map<String, Object> before = new LinkedHashMap<>();
    private final Map<String, Object> after = new LinkedHashMap<>();
    private final Map<String, Object> details = new LinkedHashMap<>();

    TxScope(WorkerRuntime rt, String txId, int attempt, TxSpec spec, JdbcTemplate jdbc) {
        this.rt = rt;
        this.txId = txId;
        this.attempt = attempt;
        this.spec = spec;
        this.jdbc = jdbc;
    }

    public String txId() {
        return txId;
    }

    public int attempt() {
        return attempt;
    }

    public Long pid() {
        return pid;
    }

    void pid(Long value) {
        this.pid = value;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public WorkerRuntime runtime() {
        return rt;
    }

    public ExperimentConfiguration config() {
        return rt.config();
    }

    public String workerId() {
        return rt.workerId();
    }

    public void emit(EventType type, String resource, String message, Map<String, Object> eventDetails) {
        rt.emit(txId, pid, type, EventSource.INSTRUMENTED, resource, message, eventDetails);
    }

    public void emitObserved(EventType type, String resource, String message, Map<String, Object> eventDetails) {
        rt.emit(txId, pid, type, EventSource.DATABASE_OBSERVED, resource, message, eventDetails);
    }

    /** READ phase: optional delay + checkpoint, READ_STARTED, the read itself, READ_COMPLETED, optional delay. */
    public <T> T read(String resource, String description, Supplier<T> reader, Function<T, Map<String, Object>> describe) {
        rt.pause(config().delayBeforeReadMs());
        rt.checkpoint("read", rt.workerId() + ": " + description);
        emit(EventType.READ_STARTED, resource, description, Map.of());
        T value = reader.get();
        Map<String, Object> d = describe.apply(value);
        emit(EventType.READ_COMPLETED, resource, description + " → " + d, d);
        rt.pause(config().delayAfterReadMs());
        return value;
    }

    /** VALIDATION phase: emits the decision and throws a business rejection when the rule fails. */
    public void validate(String resource, String rule, boolean ok, String rejectionCode, String rejectionMessage) {
        rt.checkpoint("validate", rt.workerId() + ": validate " + rule);
        emit(EventType.VALIDATION_STARTED, resource, "Validate " + rule, Map.of("rule", rule));
        emit(EventType.VALIDATION_COMPLETED, resource, ok ? "OK — " + rule : "REJECTED — " + rejectionMessage,
                Map.of("rule", rule, "valid", ok));
        if (!ok) {
            throw new BusinessRuleRejection(rejectionCode, rejectionMessage);
        }
    }

    /** Coordinated interleaving point; only the first wave/attempt is coordinated (retries run freely). */
    public void sync(String point) {
        if (attempt == 1 && spec.round() == 0) {
            rt.rendezvous(point);
        }
    }

    /**
     * WRITE phase. The worker declares a possible database wait for the duration of the statement, so the
     * observer can confirm (via pg_blocking_pids) whether it really waited for a row lock.
     */
    public <T> T write(String resource, String description, Supplier<T> writer, Function<T, Map<String, Object>> describe) {
        rt.pause(config().delayBeforeWriteMs());
        rt.checkpoint("write", rt.workerId() + ": " + description);
        emit(EventType.WRITE_STARTED, resource, description, Map.of());
        rt.block(BlockRegistry.Kind.DATABASE, "statement may wait for a row lock: " + description, pid);
        T value;
        try {
            value = writer.get();
        } finally {
            lockWaitMs += rt.unblock();
        }
        Map<String, Object> d = describe.apply(value);
        emit(EventType.WRITE_COMPLETED, resource, description + " → " + d, d);
        return value;
    }

    /** Explicit lock acquisition (SELECT … FOR UPDATE, advisory lock, JVM lock, Redis lock…). */
    public <T> T lock(BlockRegistry.Kind kind, String resource, String description, Supplier<T> acquire) {
        emit(EventType.LOCK_REQUESTED, resource, description, Map.of("lockKind", kind.name()));
        long start = System.nanoTime();
        rt.block(kind, description, pid);
        T value;
        try {
            value = acquire.get();
        } finally {
            rt.unblock();
        }
        long waited = (System.nanoTime() - start) / 1_000_000;
        lockWaitMs += waited;
        emit(EventType.LOCK_ACQUIRED, resource, description + " — acquired after " + waited + " ms",
                Map.of("lockKind", kind.name(), "waitMs", waited));
        return value;
    }

    public void lockReleased(String resource, String description) {
        emit(EventType.LOCK_RELEASED, resource, description, Map.of());
    }

    public void addLockWait(long ms) {
        lockWaitMs += ms;
    }

    long lockWaitMs() {
        return lockWaitMs;
    }

    public TxScope before(String key, Object value) {
        before.put(key, value);
        return this;
    }

    public TxScope after(String key, Object value) {
        after.put(key, value);
        return this;
    }

    public TxScope detail(String key, Object value) {
        details.put(key, value);
        return this;
    }

    Map<String, Object> before() {
        return before;
    }

    Map<String, Object> after() {
        return after;
    }

    Map<String, Object> details() {
        return details;
    }
}

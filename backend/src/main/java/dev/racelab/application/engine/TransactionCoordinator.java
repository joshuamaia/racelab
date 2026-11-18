package dev.racelab.application.engine;

import dev.racelab.application.engine.SqlErrorClassifier.Classification;
import dev.racelab.domain.model.ErrorType;
import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.TxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one logical operation of a worker as a real, independent database transaction, with bounded retries.
 * <p>
 * Each attempt obtains its own connection and persistence context from the Spring transaction manager
 * (bound to the worker thread, never shared), configures {@code lock_timeout}, {@code statement_timeout},
 * {@code idle_in_transaction_session_timeout} and {@code application_name} with {@code SET LOCAL}
 * semantics, and is committed or rolled back explicitly so that COMMIT_* / ROLLBACK_* events reflect
 * exactly what happened. Programmatic demarcation (the same API used by {@code TransactionTemplate})
 * avoids the self-invocation pitfalls of {@code @Transactional}.
 */
@Component
public class TransactionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TransactionCoordinator.class);

    private static final String INIT_SQL = """
            SELECT pg_backend_pid() AS pid,
                   set_config('application_name', ?, true),
                   set_config('lock_timeout', ?, true),
                   set_config('statement_timeout', ?, true),
                   set_config('idle_in_transaction_session_timeout', ?, true)
            """;

    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbc;
    private final SqlErrorClassifier classifier;

    public TransactionCoordinator(PlatformTransactionManager transactionManager, JdbcTemplate jdbc,
                                  SqlErrorClassifier classifier) {
        this.transactionManager = transactionManager;
        this.jdbc = jdbc;
        this.classifier = classifier;
    }

    @FunctionalInterface
    public interface TxBody {
        void run(TxScope tx) throws Exception;
    }

    public TransactionOutcome execute(WorkerRuntime rt, TxSpec spec, TxBody body) {
        return execute(rt, spec, TxGuard.NONE, body);
    }

    public TransactionOutcome execute(WorkerRuntime rt, TxSpec spec, TxGuard guard, TxBody body) {
        long start = System.nanoTime();
        List<String> txIds = new ArrayList<>();
        long lockWait = 0;
        int attempt = 0;
        while (true) {
            attempt++;
            String txId = "tx-" + rt.shortId() + "-" + spec.operation() + (attempt > 1 ? "-a" + attempt : "");
            txIds.add(txId);
            TxScope scope = new TxScope(rt, txId, attempt, spec, jdbc);
            Attempt result;
            MDC.put("transactionId", txId);
            try {
                GuardScopeImpl guardScope = new GuardScopeImpl(rt, txId);
                final int currentAttempt = attempt;
                try {
                    result = guard.around(guardScope, () -> runAttempt(rt, spec, scope, currentAttempt, body));
                } catch (RuntimeException guardFailure) {
                    // The guard itself failed (lock not acquired, cancelled…): no database transaction was opened.
                    Classification c = classifier.classify(guardFailure, rt.isCancelled());
                    rt.emit(txId, null, EventType.ROLLBACK_COMPLETED, EventSource.INSTRUMENTED, null,
                            "Transaction not started — " + c.friendlyMessage(), detailsOf(c));
                    result = new Attempt(false, c);
                }
                lockWait += scope.lockWaitMs() + guardScope.lockWaitMs;
            } finally {
                MDC.remove("transactionId");
            }
            if (result.committed) {
                return outcome(rt, txId, txIds, TxStatus.COMMITTED, attempt, start, lockWait, null, scope);
            }
            Classification c = result.classification;
            boolean retryable = c.errorType() != null && c.errorType().isRetryable() && !rt.isCancelled();
            if (retryable && attempt <= spec.retries()) {
                long backoff = spec.backoffMs() * (1L << Math.min(attempt - 1, 6));
                rt.emit(txId, scope.pid(), EventType.RETRY_SCHEDULED, EventSource.INSTRUMENTED, null,
                        "Retry " + attempt + "/" + spec.retries() + " in " + backoff + " ms after " + c.errorType(),
                        Map.of("attempt", attempt + 1, "backoffMs", backoff, "cause", c.errorType().name()));
                rt.pause(backoff);
                continue;
            }
            return outcome(rt, txId, txIds, statusOf(c), attempt, start, lockWait, c, scope);
        }
    }

    private Attempt runAttempt(WorkerRuntime rt, TxSpec spec, TxScope scope, int attempt, TxBody body) {
        rt.checkpoint("begin", rt.workerId() + ": BEGIN " + scope.txId());
        DefaultTransactionDefinition def = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        def.setIsolationLevel(spec.isolation().springLevel());
        def.setTimeout(Math.max(1, (int) Math.ceil(spec.timeoutMs() / 1000.0)));
        def.setName(scope.txId());
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            Map<String, Object> init = jdbc.queryForMap(INIT_SQL,
                    ("racelab:" + rt.experimentId() + ":" + rt.workerId()),
                    spec.lockTimeoutMs() + "ms",
                    spec.timeoutMs() + "ms",
                    Math.max(30_000, rt.config().stepTimeoutSeconds() * 1000 + 10_000) + "ms");
            scope.pid(((Number) init.get("pid")).longValue());
            rt.registerPid(scope.pid());
            Map<String, Object> started = new LinkedHashMap<>();
            started.put("isolationLevel", spec.isolation().name());
            started.put("effectiveIsolationLevel", spec.isolation().effectiveInPostgres().name());
            started.put("attempt", attempt);
            started.put("connectionPid", scope.pid());
            started.put("lockTimeoutMs", spec.lockTimeoutMs());
            rt.emit(scope.txId(), scope.pid(), EventType.TRANSACTION_STARTED, EventSource.INSTRUMENTED, null,
                    "BEGIN ISOLATION LEVEL " + spec.isolation().sqlName() + " (session pid " + scope.pid() + ")", started);

            body.run(scope);

            rt.pause(rt.config().delayBeforeCommitMs());
            rt.checkpoint("commit", rt.workerId() + ": COMMIT " + scope.txId());
            rt.throwIfCancelled();
            rt.emit(scope.txId(), scope.pid(), EventType.COMMIT_STARTED, EventSource.INSTRUMENTED, null, "COMMIT", Map.of());
            transactionManager.commit(status);
            rt.emit(scope.txId(), scope.pid(), EventType.COMMIT_COMPLETED, EventSource.INSTRUMENTED, null,
                    "Committed", Map.of());
            return new Attempt(true, null);
        } catch (Throwable ex) {
            Classification c = classifier.classify(ex, rt.isCancelled());
            publishFailure(rt, scope, c);
            if (!status.isCompleted()) {
                rt.emit(scope.txId(), scope.pid(), EventType.ROLLBACK_STARTED, EventSource.INSTRUMENTED, null,
                        "ROLLBACK", Map.of());
                try {
                    transactionManager.rollback(status);
                } catch (RuntimeException rollbackFailure) {
                    log.warn("Rollback of {} failed: {}", scope.txId(), rollbackFailure.getMessage());
                }
            }
            rt.emit(scope.txId(), scope.pid(), EventType.ROLLBACK_COMPLETED, EventSource.INSTRUMENTED, null,
                    "Rolled back — " + c.friendlyMessage(), detailsOf(c));
            if (c.errorType() == ErrorType.INTERNAL) {
                log.error("Unexpected failure in {} of experiment {}", scope.txId(), rt.experimentId(), ex);
            }
            return new Attempt(false, c);
        } finally {
            if (scope.pid() != null) {
                rt.unregisterPid(scope.pid());
            }
        }
    }

    private void publishFailure(WorkerRuntime rt, TxScope scope, Classification c) {
        if (c.errorType() == null) {
            return;
        }
        Map<String, Object> d = detailsOf(c);
        switch (c.errorType()) {
            case DEADLOCK -> {
                rt.emit(scope.txId(), scope.pid(), EventType.DEADLOCK_DETECTED, EventSource.DATABASE_OBSERVED, null,
                        "SQLSTATE 40P01 — " + c.message(), d);
                if (scope.pid() != null) {
                    rt.reportDeadlock(scope.pid(), c.detail());
                }
            }
            case SERIALIZATION_FAILURE -> rt.emit(scope.txId(), scope.pid(), EventType.SERIALIZATION_FAILURE,
                    EventSource.DATABASE_OBSERVED, null, "SQLSTATE 40001 — " + c.message(), d);
            case OPTIMISTIC_LOCK -> rt.emit(scope.txId(), scope.pid(), EventType.OPTIMISTIC_LOCK_CONFLICT,
                    EventSource.DATABASE_OBSERVED, null, c.friendlyMessage(), d);
            case INJECTED_FAULT -> rt.emit(scope.txId(), scope.pid(), EventType.FAULT_INJECTED,
                    EventSource.INSTRUMENTED, null, c.message(), d);
            default -> {
                // other failures are described by the ROLLBACK_COMPLETED event
            }
        }
    }

    private static Map<String, Object> detailsOf(Classification c) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (c.errorType() != null) {
            d.put("errorType", c.errorType().name());
        }
        if (c.sqlState() != null) {
            d.put("sqlState", c.sqlState());
        }
        if (c.message() != null) {
            d.put("message", c.message());
        }
        if (c.detail() != null) {
            d.put("detail", c.detail());
        }
        return d;
    }

    private static TxStatus statusOf(Classification c) {
        if (c.errorType() == null) {
            return TxStatus.ROLLED_BACK;
        }
        return switch (c.errorType()) {
            case BUSINESS_RULE -> TxStatus.REJECTED;
            case CANCELLED -> TxStatus.CANCELLED;
            case INTERNAL, CONNECTION_FAILURE -> TxStatus.FAILED;
            default -> TxStatus.ROLLED_BACK;
        };
    }

    private static TransactionOutcome outcome(WorkerRuntime rt, String txId, List<String> txIds, TxStatus status,
                                              int attempts, long startNanos, long lockWait, Classification c,
                                              TxScope scope) {
        long latency = (System.nanoTime() - startNanos) / 1_000_000;
        boolean expected = c == null || c.errorType() == null || c.errorType().isExpectedInExperiments();
        return new TransactionOutcome(rt.workerId(), txId, List.copyOf(txIds), rt.instanceId(), status, attempts,
                latency, lockWait, c == null ? null : c.sqlState(), c == null ? null : c.errorType(),
                c == null ? null : c.message(), c == null ? null : c.friendlyMessage(), status != TxStatus.COMMITTED && expected,
                new LinkedHashMap<>(scope.before()), new LinkedHashMap<>(scope.after()),
                new LinkedHashMap<>(scope.details()));
    }

    private record Attempt(boolean committed, Classification classification) {
    }

    private static final class GuardScopeImpl implements TxGuard.GuardScope {
        private final WorkerRuntime rt;
        private final String txId;
        private long lockWaitMs;

        GuardScopeImpl(WorkerRuntime rt, String txId) {
            this.rt = rt;
            this.txId = txId;
        }

        @Override
        public String transactionId() {
            return txId;
        }

        @Override
        public WorkerRuntime runtime() {
            return rt;
        }

        @Override
        public void lockRequested(String resource, String description, String kind) {
            rt.emit(txId, null, EventType.LOCK_REQUESTED, EventSource.INSTRUMENTED, resource, description,
                    Map.of("lockKind", kind));
        }

        @Override
        public void lockAcquired(String resource, String description, String kind, long waitMs) {
            lockWaitMs += waitMs;
            rt.emit(txId, null, EventType.LOCK_ACQUIRED, EventSource.INSTRUMENTED, resource,
                    description + " — acquired after " + waitMs + " ms", Map.of("lockKind", kind, "waitMs", waitMs));
        }

        @Override
        public void lockWaiting(String resource, String description) {
            rt.emit(txId, null, EventType.LOCK_WAITING, EventSource.INSTRUMENTED, resource, description, Map.of());
        }

        @Override
        public void lockReleased(String resource, String description) {
            rt.emit(txId, null, EventType.LOCK_RELEASED, EventSource.INSTRUMENTED, resource, description, Map.of());
        }
    }
}

package dev.racelab.application.engine;

/**
 * Exceptions raised by scenario code to drive the outcome of a transaction. None of them simulates a
 * database error: database failures (deadlock, serialization failure, unique violation, lock timeout)
 * always come from PostgreSQL itself.
 */
public final class ScenarioExceptions {

    private ScenarioExceptions() {
    }

    /** A business rule rejected the operation (insufficient balance, sold out, seat taken…). */
    public static class BusinessRuleRejection extends RuntimeException {
        private final String rule;

        public BusinessRuleRejection(String rule, String message) {
            super(message);
            this.rule = rule;
        }

        public String rule() {
            return rule;
        }
    }

    /** The experiment was cancelled (user action, step timeout, shutdown). */
    public static class ExperimentCancelledException extends RuntimeException {
        public ExperimentCancelledException(String message) {
            super(message);
        }
    }

    /**
     * A fault injected on purpose to model a process crash (e.g. the service dies between the database
     * commit and the message publication). Always reported as {@code FAULT_INJECTED}, never as a DB error.
     */
    public static class InjectedFault extends RuntimeException {
        public InjectedFault(String message) {
            super(message);
        }
    }

    /** The scenario rolls back on purpose (e.g. the writer of a dirty-read probe). */
    public static class IntentionalRollback extends RuntimeException {
        public IntentionalRollback(String message) {
            super(message);
        }
    }

    /** A non-database lock (ReentrantLock, Redis) could not be acquired in time, or a fencing token was stale. */
    public static class LockNotAcquiredException extends RuntimeException {
        public LockNotAcquiredException(String message) {
            super(message);
        }
    }

    /**
     * A version-checked UPDATE affected zero rows (JDBC optimistic locking). The row count comes from
     * PostgreSQL; this exception only reports it.
     */
    public static class OptimisticConflictException extends RuntimeException {
        public OptimisticConflictException(String message) {
            super(message);
        }
    }
}

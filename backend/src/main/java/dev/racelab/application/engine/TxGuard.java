package dev.racelab.application.engine;

import java.util.function.Supplier;

/**
 * Wraps a whole transaction attempt (BEGIN … COMMIT) with a non-database lock: {@code synchronized},
 * {@link java.util.concurrent.locks.ReentrantLock} or a Redis lock. The lock must cover the commit,
 * otherwise another worker could read the row before the change is visible.
 */
public interface TxGuard {

    TxGuard NONE = new TxGuard() {
        @Override
        public <T> T around(GuardScope scope, Supplier<T> attempt) {
            return attempt.get();
        }
    };

    <T> T around(GuardScope scope, Supplier<T> attempt);

    /** What a guard can do while wrapping an attempt. */
    interface GuardScope {
        String transactionId();

        WorkerRuntime runtime();

        void lockRequested(String resource, String description, String kind);

        void lockAcquired(String resource, String description, String kind, long waitMs);

        void lockWaiting(String resource, String description);

        void lockReleased(String resource, String description);
    }
}

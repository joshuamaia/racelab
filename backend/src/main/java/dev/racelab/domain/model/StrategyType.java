package dev.racelab.domain.model;

public enum StrategyType {
    NONE,
    OPTIMISTIC_LOCKING,
    PESSIMISTIC_LOCKING,
    ATOMIC_UPDATE,
    ATOMIC_UNGUARDED,
    SERIALIZABLE,
    JAVA_SYNCHRONIZED,
    REENTRANT_LOCK,
    ADVISORY_LOCK,
    REDIS_LOCK,
    FENCING_TOKEN,
    UNIQUE_CONSTRAINT,
    IDEMPOTENCY_KEY,
    LOCK_ORDERING,
    ATOMIC_CLAIM,
    SKIP_LOCKED,
    OUTBOX,
    OUTBOX_NAIVE_CONSUMER,
    ISOLATION_LEVEL;

    public boolean requiresRedis() {
        return this == REDIS_LOCK || this == FENCING_TOKEN;
    }
}

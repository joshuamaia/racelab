package dev.racelab.domain.model;

public enum ErrorType {
    DEADLOCK(true),
    SERIALIZATION_FAILURE(true),
    OPTIMISTIC_LOCK(true),
    LOCK_TIMEOUT(true),
    UNIQUE_VIOLATION(true),
    TRANSACTION_TIMEOUT(false),
    CONNECTION_FAILURE(false),
    CANCELLED(true),
    BUSINESS_RULE(true),
    LOCK_NOT_ACQUIRED(true),
    INJECTED_FAULT(true),
    INTERNAL(false);

    private final boolean expectedInExperiments;

    ErrorType(boolean expectedInExperiments) {
        this.expectedInExperiments = expectedInExperiments;
    }

    /** Whether this failure is a legitimate outcome of a concurrency experiment (vs. an internal application failure). */
    public boolean isExpectedInExperiments() {
        return expectedInExperiments;
    }

    public boolean isRetryable() {
        return this == DEADLOCK || this == SERIALIZATION_FAILURE || this == OPTIMISTIC_LOCK || this == LOCK_TIMEOUT;
    }
}

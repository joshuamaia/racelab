package dev.racelab.domain.model;

public enum TxStatus {
    /** Transaction committed. */
    COMMITTED,
    /** Rolled back because of a concurrency conflict detected by the database/ORM (deadlock, 40001, version conflict…) or by design. */
    ROLLED_BACK,
    /** Rolled back because a business rule rejected the operation (insufficient balance, sold out…). */
    REJECTED,
    /** Technical failure not expected by the experiment. */
    FAILED,
    CANCELLED
}

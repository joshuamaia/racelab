package dev.racelab.domain.model;

/**
 * Distinguishes what the application itself instrumented from what was actually observed
 * in PostgreSQL (pg_blocking_pids, pg_stat_activity, SQLSTATE codes, error details).
 */
public enum EventSource { INSTRUMENTED, DATABASE_OBSERVED }

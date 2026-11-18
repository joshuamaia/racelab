package dev.racelab.domain.model;

/** Fault injection points (process crashes), used only by the messaging/outbox experiments. */
public enum FailureMode { NONE, CRASH_AFTER_COMMIT, CRASH_BEFORE_COMMIT, RELAY_CRASH_BEFORE_ACK }

package dev.racelab.domain.model;

public enum ExperimentStatus {
    CREATED, RUNNING, COMPLETED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}

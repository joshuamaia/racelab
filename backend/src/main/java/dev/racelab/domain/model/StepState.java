package dev.racelab.domain.model;

import java.time.Instant;
import java.util.List;

public record StepState(boolean active, List<Pending> pending, List<Blocked> blocked, String suggestedWorkerId,
                        Instant deadline) {

    public record Pending(String workerId, String step, String description, Instant arrivedAt) {
    }

    public record Blocked(String workerId, String reason) {
    }
}

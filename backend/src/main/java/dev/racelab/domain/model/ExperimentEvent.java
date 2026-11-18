package dev.racelab.domain.model;

import java.time.Instant;
import java.util.Map;

public record ExperimentEvent(
        String experimentId,
        long sequence,
        String transactionId,
        String workerId,
        String threadName,
        String instanceId,
        String correlationId,
        Long connectionPid,
        EventType eventType,
        String resource,
        Instant timestamp,
        long elapsedTimeMs,
        EventSource source,
        String message,
        Map<String, Object> details) {
}

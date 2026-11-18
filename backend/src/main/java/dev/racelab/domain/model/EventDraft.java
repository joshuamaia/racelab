package dev.racelab.domain.model;

import java.util.Map;

/** An event before the publisher assigns sequence number, timestamp and elapsed time. */
public record EventDraft(
        String transactionId,
        String workerId,
        String threadName,
        String instanceId,
        Long connectionPid,
        EventType eventType,
        String resource,
        EventSource source,
        String message,
        Map<String, Object> details) {

    public static EventDraft experiment(EventType type, String message, Map<String, Object> details) {
        return new EventDraft(null, null, Thread.currentThread().getName(), null, null, type, null,
                EventSource.INSTRUMENTED, message, details == null ? Map.of() : details);
    }
}

package dev.racelab.domain.model;

import java.util.Map;

/** Result of an isolation probe: whether a read phenomenon was actually reproduced in PostgreSQL. */
public record Observation(
        String phenomenon,
        IsolationLevel isolationLevel,
        IsolationLevel effectiveIsolationLevel,
        boolean reproduced,
        String outcome,
        Map<String, Object> values,
        String note) {
}

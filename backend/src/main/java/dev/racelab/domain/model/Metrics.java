package dev.racelab.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record Metrics(
        long totalTimeMs,
        double avgLatencyMs,
        long p50LatencyMs,
        long p95LatencyMs,
        long maxLatencyMs,
        double throughputTps,
        int transactionsStarted,
        int attempts,
        int committed,
        int rolledBack,
        int businessRejections,
        int conflicts,
        int deadlocks,
        int serializationFailures,
        int optimisticConflicts,
        int uniqueViolations,
        int lockTimeouts,
        int retries,
        long lockWaitTimeMs,
        int inconsistencies) {

    public Map<String, Number> asMap() {
        Map<String, Number> m = new LinkedHashMap<>();
        m.put("totalTimeMs", totalTimeMs);
        m.put("avgLatencyMs", avgLatencyMs);
        m.put("p50LatencyMs", p50LatencyMs);
        m.put("p95LatencyMs", p95LatencyMs);
        m.put("maxLatencyMs", maxLatencyMs);
        m.put("throughputTps", throughputTps);
        m.put("transactionsStarted", transactionsStarted);
        m.put("attempts", attempts);
        m.put("committed", committed);
        m.put("rolledBack", rolledBack);
        m.put("businessRejections", businessRejections);
        m.put("conflicts", conflicts);
        m.put("deadlocks", deadlocks);
        m.put("serializationFailures", serializationFailures);
        m.put("optimisticConflicts", optimisticConflicts);
        m.put("uniqueViolations", uniqueViolations);
        m.put("lockTimeouts", lockTimeouts);
        m.put("retries", retries);
        m.put("lockWaitTimeMs", lockWaitTimeMs);
        m.put("inconsistencies", inconsistencies);
        return m;
    }
}

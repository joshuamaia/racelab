package dev.racelab.application.engine;

import dev.racelab.domain.model.ErrorType;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.model.Metrics;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.TxStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/** Computes the metrics of an experiment from the real outcomes and events (never from fixed values). */
@Component
public class ExperimentMetricsCollector {

    public Metrics collect(List<TransactionOutcome> outcomes, List<ExperimentEvent> events, long totalTimeMs,
                           int inconsistencies) {
        List<TransactionOutcome> ops = outcomes.stream().filter(o -> o.transactionId() != null).toList();
        long[] latencies = ops.stream().mapToLong(TransactionOutcome::latencyMs).sorted().toArray();
        double avg = latencies.length == 0 ? 0 : round(java.util.Arrays.stream(latencies).average().orElse(0));
        int committed = (int) ops.stream().filter(o -> o.status() == TxStatus.COMMITTED).count();
        int rejections = (int) ops.stream().filter(o -> o.status() == TxStatus.REJECTED).count();
        int attempts = ops.stream().mapToInt(TransactionOutcome::attempts).sum();
        long lockWait = ops.stream().mapToLong(TransactionOutcome::lockWaitMs).sum();
        int rollbacks = count(events, EventType.ROLLBACK_COMPLETED);
        int deadlocks = count(events, EventType.DEADLOCK_DETECTED);
        int serialization = count(events, EventType.SERIALIZATION_FAILURE);
        int optimistic = count(events, EventType.OPTIMISTIC_LOCK_CONFLICT);
        int unique = (int) events.stream().filter(e -> e.eventType() == EventType.ROLLBACK_COMPLETED
                && ErrorType.UNIQUE_VIOLATION.name().equals(e.details().get("errorType"))).count();
        int lockTimeouts = (int) events.stream().filter(e -> e.eventType() == EventType.ROLLBACK_COMPLETED
                && ErrorType.LOCK_TIMEOUT.name().equals(e.details().get("errorType"))).count();
        int retries = count(events, EventType.RETRY_SCHEDULED);
        double throughput = totalTimeMs <= 0 ? 0 : round(committed * 1000.0 / totalTimeMs);
        return new Metrics(totalTimeMs, avg, percentile(latencies, 50), percentile(latencies, 95),
                latencies.length == 0 ? 0 : latencies[latencies.length - 1], throughput, ops.size(), attempts,
                committed, rollbacks, rejections, deadlocks + serialization + optimistic + unique, deadlocks,
                serialization, optimistic, unique, lockTimeouts, retries, lockWait, inconsistencies);
    }

    static long percentile(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    private static int count(List<ExperimentEvent> events, EventType type) {
        return (int) events.stream().filter(e -> e.eventType() == type).count();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

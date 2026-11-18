package dev.racelab.domain.model;

import java.util.List;
import java.util.Map;

/** Outcome of one logical operation (one business transaction, possibly retried several times). */
public record TransactionOutcome(
        String workerId,
        String transactionId,
        List<String> transactionIds,
        String instanceId,
        TxStatus status,
        int attempts,
        long latencyMs,
        long lockWaitMs,
        String sqlState,
        ErrorType errorType,
        String message,
        String friendlyMessage,
        boolean expectedFailure,
        Map<String, Object> before,
        Map<String, Object> after,
        Map<String, Object> details) {

    public boolean committed() {
        return status == TxStatus.COMMITTED;
    }

    public Object detail(String key) {
        return details == null ? null : details.get(key);
    }
}

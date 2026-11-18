package dev.racelab.application.engine;

import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.IsolationLevel;

/**
 * How one logical operation is executed: isolation, timeouts and the bounded retry policy.
 *
 * @param operation  1-based operation number of the worker (used in transaction ids)
 * @param round      0 for the first wave of operations: only that wave is coordinated by rendezvous points
 */
public record TxSpec(int operation, int round, IsolationLevel isolation, int retries, long backoffMs,
                     int timeoutMs, int lockTimeoutMs, String label) {

    public static TxSpec from(ExperimentConfiguration c, int operation, int round, String label) {
        return new TxSpec(operation, round, c.isolationLevel(), c.retries(), c.backoffMs(), c.transactionTimeoutMs(),
                c.lockTimeoutMs(), label);
    }

    public TxSpec withIsolation(IsolationLevel level) {
        return new TxSpec(operation, round, level, retries, backoffMs, timeoutMs, lockTimeoutMs, label);
    }

    public TxSpec withRetries(int value) {
        return new TxSpec(operation, round, isolation, value, backoffMs, timeoutMs, lockTimeoutMs, label);
    }
}

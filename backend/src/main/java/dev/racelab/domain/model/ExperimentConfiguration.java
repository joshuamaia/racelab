package dev.racelab.domain.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Configuration of an experiment execution. Every field is nullable so the same record can be used
 * as a partial override coming from the API; {@link #withDefaults(ExperimentConfiguration)} fills the gaps.
 * The limits protect the infrastructure (threads, connections, open transactions).
 */
public record ExperimentConfiguration(
        @NotNull @Min(1) @Max(16) Integer threads,
        @NotNull @Min(1) @Max(200) Integer transactions,
        @NotNull @DecimalMin("0.00") @DecimalMax("1000000.00") BigDecimal initialValue,
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000.00") BigDecimal operationValue,
        @NotNull @Min(1) @Max(20) Integer resourceCount,
        @Pattern(regexp = "^[A-Za-z0-9-]{1,32}$") String resourceCode,
        @NotNull @Min(0) @Max(5000) Integer delayBeforeReadMs,
        @NotNull @Min(0) @Max(5000) Integer delayAfterReadMs,
        @NotNull @Min(0) @Max(5000) Integer delayBeforeWriteMs,
        @NotNull @Min(0) @Max(5000) Integer delayBeforeCommitMs,
        @NotNull IsolationLevel isolationLevel,
        @NotNull @Min(1000) @Max(60000) Integer transactionTimeoutMs,
        @NotNull @Min(100) @Max(30000) Integer lockTimeoutMs,
        @NotNull @Min(0) @Max(10) Integer retries,
        @NotNull @Min(0) @Max(5000) Integer backoffMs,
        @NotNull Long seed,
        @NotNull ExecutionMode executionMode,
        @NotNull Topology topology,
        @NotNull Boolean coordinated,
        @NotNull @Min(200) @Max(30000) Integer lockTtlMs,
        @NotNull FailureMode failureMode,
        @NotNull @Min(10) @Max(600) Integer stepTimeoutSeconds) {

    public static ExperimentConfiguration empty() {
        return new ExperimentConfiguration(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    public ExperimentConfiguration withDefaults(ExperimentConfiguration d) {
        if (d == null) {
            return this;
        }
        return new ExperimentConfiguration(
                or(threads, d.threads), or(transactions, d.transactions),
                money(or(initialValue, d.initialValue)), money(or(operationValue, d.operationValue)),
                or(resourceCount, d.resourceCount), or(resourceCode, d.resourceCode),
                or(delayBeforeReadMs, d.delayBeforeReadMs), or(delayAfterReadMs, d.delayAfterReadMs),
                or(delayBeforeWriteMs, d.delayBeforeWriteMs), or(delayBeforeCommitMs, d.delayBeforeCommitMs),
                or(isolationLevel, d.isolationLevel), or(transactionTimeoutMs, d.transactionTimeoutMs),
                or(lockTimeoutMs, d.lockTimeoutMs), or(retries, d.retries), or(backoffMs, d.backoffMs),
                or(seed, d.seed), or(executionMode, d.executionMode), or(topology, d.topology),
                or(coordinated, d.coordinated), or(lockTtlMs, d.lockTtlMs), or(failureMode, d.failureMode),
                or(stepTimeoutSeconds, d.stepTimeoutSeconds));
    }

    public ExperimentConfiguration withIsolation(IsolationLevel level) {
        return new ExperimentConfiguration(threads, transactions, initialValue, operationValue, resourceCount,
                resourceCode, delayBeforeReadMs, delayAfterReadMs, delayBeforeWriteMs, delayBeforeCommitMs,
                level, transactionTimeoutMs, lockTimeoutMs, retries, backoffMs, seed, executionMode, topology,
                coordinated, lockTtlMs, failureMode, stepTimeoutSeconds);
    }

    public ExperimentConfiguration withExecutionMode(ExecutionMode mode) {
        return new ExperimentConfiguration(threads, transactions, initialValue, operationValue, resourceCount,
                resourceCode, delayBeforeReadMs, delayAfterReadMs, delayBeforeWriteMs, delayBeforeCommitMs,
                isolationLevel, transactionTimeoutMs, lockTimeoutMs, retries, backoffMs, seed, mode, topology,
                coordinated, lockTtlMs, failureMode, stepTimeoutSeconds);
    }

    public boolean stepByStep() {
        return executionMode == ExecutionMode.STEP_BY_STEP;
    }

    /** Operation value interpreted as an integer quantity (inventory, …). */
    public int quantity() {
        return Math.max(1, operationValue.setScale(0, RoundingMode.DOWN).intValue());
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
}

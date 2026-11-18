package dev.racelab.scenario.support;

import dev.racelab.domain.model.ExecutionMode;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.FailureMode;
import dev.racelab.domain.model.IsolationLevel;
import dev.racelab.domain.model.Topology;

import java.math.BigDecimal;

/** Fluent builder for complete experiment configurations (scenario defaults and catalog presets). */
public final class ConfigBuilder {

    private int threads = 2;
    private int transactions = 2;
    private BigDecimal initialValue = new BigDecimal("1000.00");
    private BigDecimal operationValue = new BigDecimal("800.00");
    private int resourceCount = 1;
    private String resourceCode;
    private int delayBeforeReadMs;
    private int delayAfterReadMs;
    private int delayBeforeWriteMs;
    private int delayBeforeCommitMs;
    private IsolationLevel isolationLevel = IsolationLevel.READ_COMMITTED;
    private int transactionTimeoutMs = 10_000;
    private int lockTimeoutMs = 5_000;
    private int retries;
    private int backoffMs = 100;
    private long seed = 42;
    private ExecutionMode executionMode = ExecutionMode.CONCURRENT;
    private Topology topology = Topology.SINGLE_JVM;
    private boolean coordinated = true;
    private int lockTtlMs = 1_500;
    private FailureMode failureMode = FailureMode.NONE;
    private int stepTimeoutSeconds = 120;

    public static ConfigBuilder defaults() {
        return new ConfigBuilder();
    }

    public static ConfigBuilder from(ExperimentConfiguration c) {
        ConfigBuilder b = new ConfigBuilder();
        b.threads = c.threads();
        b.transactions = c.transactions();
        b.initialValue = c.initialValue();
        b.operationValue = c.operationValue();
        b.resourceCount = c.resourceCount();
        b.resourceCode = c.resourceCode();
        b.delayBeforeReadMs = c.delayBeforeReadMs();
        b.delayAfterReadMs = c.delayAfterReadMs();
        b.delayBeforeWriteMs = c.delayBeforeWriteMs();
        b.delayBeforeCommitMs = c.delayBeforeCommitMs();
        b.isolationLevel = c.isolationLevel();
        b.transactionTimeoutMs = c.transactionTimeoutMs();
        b.lockTimeoutMs = c.lockTimeoutMs();
        b.retries = c.retries();
        b.backoffMs = c.backoffMs();
        b.seed = c.seed();
        b.executionMode = c.executionMode();
        b.topology = c.topology();
        b.coordinated = c.coordinated();
        b.lockTtlMs = c.lockTtlMs();
        b.failureMode = c.failureMode();
        b.stepTimeoutSeconds = c.stepTimeoutSeconds();
        return b;
    }

    public ConfigBuilder threads(int v) { threads = v; return this; }

    public ConfigBuilder transactions(int v) { transactions = v; return this; }

    public ConfigBuilder initialValue(String v) { initialValue = new BigDecimal(v); return this; }

    public ConfigBuilder operationValue(String v) { operationValue = new BigDecimal(v); return this; }

    public ConfigBuilder resourceCount(int v) { resourceCount = v; return this; }

    public ConfigBuilder resourceCode(String v) { resourceCode = v; return this; }

    public ConfigBuilder delayBeforeReadMs(int v) { delayBeforeReadMs = v; return this; }

    public ConfigBuilder delayAfterReadMs(int v) { delayAfterReadMs = v; return this; }

    public ConfigBuilder delayBeforeWriteMs(int v) { delayBeforeWriteMs = v; return this; }

    public ConfigBuilder delayBeforeCommitMs(int v) { delayBeforeCommitMs = v; return this; }

    public ConfigBuilder isolation(IsolationLevel v) { isolationLevel = v; return this; }

    public ConfigBuilder transactionTimeoutMs(int v) { transactionTimeoutMs = v; return this; }

    public ConfigBuilder lockTimeoutMs(int v) { lockTimeoutMs = v; return this; }

    public ConfigBuilder retries(int v) { retries = v; return this; }

    public ConfigBuilder backoffMs(int v) { backoffMs = v; return this; }

    public ConfigBuilder seed(long v) { seed = v; return this; }

    public ConfigBuilder mode(ExecutionMode v) { executionMode = v; return this; }

    public ConfigBuilder topology(Topology v) { topology = v; return this; }

    public ConfigBuilder coordinated(boolean v) { coordinated = v; return this; }

    public ConfigBuilder lockTtlMs(int v) { lockTtlMs = v; return this; }

    public ConfigBuilder failureMode(FailureMode v) { failureMode = v; return this; }

    public ConfigBuilder stepTimeoutSeconds(int v) { stepTimeoutSeconds = v; return this; }

    public ExperimentConfiguration build() {
        return new ExperimentConfiguration(threads, transactions, initialValue, operationValue, resourceCount,
                resourceCode, delayBeforeReadMs, delayAfterReadMs, delayBeforeWriteMs, delayBeforeCommitMs,
                isolationLevel, transactionTimeoutMs, lockTimeoutMs, retries, backoffMs, seed, executionMode, topology,
                coordinated, lockTtlMs, failureMode, stepTimeoutSeconds);
    }
}

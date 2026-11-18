package dev.racelab.domain.model;

import java.time.Instant;

/** Persisted metadata of an experiment (history entry). */
public record ExperimentRecord(
        String id,
        String name,
        String presetId,
        String scenarioId,
        Category category,
        StrategyType strategy,
        ExperimentStatus status,
        ExecutionMode executionMode,
        String instanceId,
        String comparisonId,
        boolean warmup,
        String rerunOf,
        String correlationId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        Boolean consistent,
        int violationCount,
        int eventCount,
        String errorMessage,
        ExperimentConfiguration configuration) {
}

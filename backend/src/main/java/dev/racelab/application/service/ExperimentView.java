package dev.racelab.application.service;

import dev.racelab.domain.model.Category;
import dev.racelab.domain.model.ExecutionMode;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.ExperimentRecord;
import dev.racelab.domain.model.ExperimentStatus;
import dev.racelab.domain.model.StepState;
import dev.racelab.domain.model.StrategyType;

import java.time.Instant;

/** API view of an experiment (history entry + live step state). */
public record ExperimentView(
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
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        Boolean consistent,
        int violationCount,
        int eventCount,
        ExperimentConfiguration configuration,
        String errorMessage,
        StepState stepState) {

    public static ExperimentView of(ExperimentRecord r, StepState stepState) {
        return new ExperimentView(r.id(), r.name(), r.presetId(), r.scenarioId(), r.category(), r.strategy(), r.status(),
                r.executionMode(), r.instanceId(), r.comparisonId(), r.warmup(), r.rerunOf(), r.createdAt(),
                r.startedAt(), r.finishedAt(), r.durationMs(), r.consistent(), r.violationCount(), r.eventCount(),
                r.configuration(), r.errorMessage(), stepState);
    }
}

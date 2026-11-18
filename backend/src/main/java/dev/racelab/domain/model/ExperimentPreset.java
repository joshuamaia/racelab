package dev.racelab.domain.model;

import java.util.List;

/** An entry of the experiment catalog: a scenario + strategy + configuration with its pedagogical content. */
public record ExperimentPreset(
        String id,
        String name,
        Category category,
        Difficulty difficulty,
        String problem,
        String businessScenario,
        String scenarioId,
        StrategyType strategy,
        List<StrategyType> availableStrategies,
        ExperimentConfiguration configuration,
        String expectedResult,
        String explanation,
        List<String> recommendations,
        Integer demo,
        List<String> tags) {
}

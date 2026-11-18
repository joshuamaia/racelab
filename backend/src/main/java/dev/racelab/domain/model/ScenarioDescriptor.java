package dev.racelab.domain.model;

import java.util.List;
import java.util.Map;

public record ScenarioDescriptor(
        String id,
        String name,
        Category category,
        String description,
        String businessContext,
        String invariant,
        List<StrategyType> supportedStrategies,
        StrategyType defaultStrategy,
        boolean supportsStepMode,
        boolean supportsDistributed,
        List<String> relevantParameters,
        Map<String, String> parameterLabels,
        List<TemplateOption> templates,
        ExperimentConfiguration defaults) {

    public record TemplateOption(String code, String label) {
    }

    public boolean supports(StrategyType strategy) {
        return supportedStrategies.contains(strategy);
    }
}

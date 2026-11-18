package dev.racelab.domain.model;

import java.util.List;

public record Violation(
        ViolationKind kind,
        String rule,
        String description,
        String expected,
        String actual,
        String difference,
        List<String> transactions,
        Severity severity,
        String possibleCause,
        List<StrategyType> recommendedStrategies) {
}

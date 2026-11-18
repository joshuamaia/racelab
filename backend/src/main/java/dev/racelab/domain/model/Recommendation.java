package dev.racelab.domain.model;

import java.util.List;

public record Recommendation(
        String id,
        String title,
        String trigger,
        String justification,
        List<String> benefits,
        List<String> tradeOffs,
        List<StrategyType> strategies,
        int priority) {
}

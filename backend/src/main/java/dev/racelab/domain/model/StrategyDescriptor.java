package dev.racelab.domain.model;

import java.util.List;

public record StrategyDescriptor(
        StrategyType id,
        String name,
        String family,
        String scope,
        String description,
        List<String> advantages,
        List<String> limitations) {
}

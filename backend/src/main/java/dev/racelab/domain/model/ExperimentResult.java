package dev.racelab.domain.model;

import java.util.List;
import java.util.Map;

public record ExperimentResult(
        String experimentId,
        ExperimentStatus status,
        boolean consistent,
        String summary,
        List<ResourceState> initialState,
        List<ResourceState> finalState,
        List<StateRow> stateRows,
        List<TransactionOutcome> transactions,
        List<Violation> violations,
        List<Observation> observations,
        Metrics metrics,
        List<Recommendation> recommendations,
        LockGraph lockGraph,
        Map<String, Object> environment) {
}

package dev.racelab.application.engine;

import dev.racelab.domain.model.Observation;
import dev.racelab.domain.model.ResourceState;
import dev.racelab.domain.model.StateRow;
import dev.racelab.domain.model.Violation;

import java.util.List;

/** What a scenario observed: real initial/final state, expected vs actual rows, violations, observations. */
public record ScenarioReport(
        String summary,
        List<ResourceState> initialState,
        List<ResourceState> finalState,
        List<StateRow> stateRows,
        List<Violation> violations,
        List<Observation> observations) {
}

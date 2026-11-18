package dev.racelab.application.engine;

import dev.racelab.application.port.RemoteWorkerGateway.RemoteWorkerRequest;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.ScenarioDescriptor;

import java.util.Optional;

/**
 * An executable scenario. Implementations seed isolated data, run real concurrent transactions through
 * the {@link ExperimentContext}, then read the real final state and evaluate the business invariants.
 * The engine enriches the returned {@link ScenarioReport} with metrics, lock graph and recommendations.
 */
public interface Experiment {

    ScenarioDescriptor descriptor();

    default String getType() {
        return descriptor().id();
    }

    ScenarioReport execute(ExperimentConfiguration configuration, ExperimentContext context);

    /** Worker that can run in another JVM (distributed topology). */
    default Optional<WorkerTask> remoteWorker(RemoteWorkerRequest request) {
        return Optional.empty();
    }
}

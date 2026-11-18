package dev.racelab.application.port;

import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.model.TransactionOutcome;

import java.util.List;
import java.util.Map;

/** Executes workers on the peer backend instance (another, independent JVM). */
public interface RemoteWorkerGateway {

    boolean isPeerAvailable();

    String peerInstanceId();

    List<TransactionOutcome> execute(RemoteWorkerRequest request);

    record RemoteWorkerRequest(
            String experimentId,
            String scenarioId,
            StrategyType strategy,
            ExperimentConfiguration configuration,
            String workerId,
            int workerIndex,
            List<Integer> operations,
            String callbackBaseUrl,
            String correlationId,
            Map<String, Object> data) {
    }
}

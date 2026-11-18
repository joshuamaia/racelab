package dev.racelab.application.engine;

import dev.racelab.application.port.DistributedLockService;
import dev.racelab.application.port.MessageBroker;
import dev.racelab.application.port.RemoteWorkerGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/** Shared infrastructure handed to every {@link ExperimentContext}. */
@Component
public record EngineServices(
        JdbcTemplate jdbc,
        PlatformTransactionManager transactionManager,
        TransactionCoordinator coordinator,
        ExperimentEventPublisher publisher,
        ExperimentCancellationManager cancellations,
        Clock clock,
        RemoteWorkerGateway remoteWorkers,
        DistributedLockService distributedLocks,
        MessageBroker broker,
        @Value("${racelab.instance-id}") String instanceId,
        @Value("${racelab.self-url}") String selfUrl) {
}

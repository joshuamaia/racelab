package dev.racelab.application.engine;

import dev.racelab.domain.model.EventDraft;
import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.port.EventStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Registry of the live event buses of the experiments owned by this backend instance. */
@Component
public class ExperimentEventPublisher {

    private static final Duration RETENTION = Duration.ofMinutes(15);

    private final ConcurrentMap<String, LiveEventBus> buses = new ConcurrentHashMap<>();
    private final EventStore store;
    private final Clock clock;
    private final String instanceId;

    public ExperimentEventPublisher(EventStore store, Clock clock, @Value("${racelab.instance-id}") String instanceId) {
        this.store = store;
        this.clock = clock;
        this.instanceId = instanceId;
    }

    public LiveEventBus open(String experimentId, String correlationId) {
        return buses.computeIfAbsent(experimentId, id -> new LiveEventBus(id, correlationId, instanceId, store, clock, 0));
    }

    /** Re-opens a bus for an experiment created earlier (e.g. started after a restart), continuing the sequence. */
    public LiveEventBus reopen(String experimentId, String correlationId, long lastSequence) {
        return buses.compute(experimentId, (id, existing) -> existing != null && !existing.isClosed()
                ? existing
                : new LiveEventBus(id, correlationId, instanceId, store, clock, lastSequence));
    }

    public Optional<LiveEventBus> bus(String experimentId) {
        return Optional.ofNullable(buses.get(experimentId));
    }

    public ExperimentEvent publish(String experimentId, EventDraft draft) {
        LiveEventBus bus = buses.get(experimentId);
        return bus == null ? null : bus.publish(draft);
    }

    public void discard(String experimentId) {
        buses.remove(experimentId);
    }

    @Scheduled(fixedDelay = 60_000)
    void evictClosedBuses() {
        Instant threshold = clock.instant().minus(RETENTION);
        buses.values().removeIf(b -> b.isClosed() && b.closedAt() != null && b.closedAt().isBefore(threshold)
                && b.subscriberCount() == 0);
    }
}

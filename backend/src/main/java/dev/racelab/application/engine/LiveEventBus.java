package dev.racelab.application.engine;

import dev.racelab.domain.model.EventDraft;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.model.ExperimentStatus;
import dev.racelab.domain.port.EventStore;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory, strictly ordered event log of one experiment. Sequence numbers are assigned under the bus
 * monitor, so every subscriber sees the same total order; replay + subscription is atomic, so a reconnecting
 * client never misses an event between the replay and the live stream.
 */
public final class LiveEventBus {

    static final int MAX_EVENTS = 50_000;

    private final String experimentId;
    private final String correlationId;
    private final String defaultInstanceId;
    private final EventStore store;
    private final Clock clock;
    private final List<ExperimentEvent> events = new ArrayList<>();
    private final List<EventSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private long sequence;
    private long startNanos = -1;
    private volatile ExperimentStatus endStatus;
    private volatile Instant closedAt;
    private int dropped;

    LiveEventBus(String experimentId, String correlationId, String defaultInstanceId, EventStore store, Clock clock,
                 long initialSequence) {
        this.experimentId = experimentId;
        this.correlationId = correlationId;
        this.defaultInstanceId = defaultInstanceId;
        this.store = store;
        this.clock = clock;
        this.sequence = initialSequence;
    }

    public String experimentId() {
        return experimentId;
    }

    public synchronized ExperimentEvent publish(EventDraft d) {
        if (endStatus != null) {
            return null;
        }
        if (d.eventType() == EventType.EXPERIMENT_STARTED && startNanos < 0) {
            startNanos = System.nanoTime();
        }
        if (events.size() >= MAX_EVENTS && !d.eventType().isTerminal()) {
            dropped++;
            return null;
        }
        long elapsed = startNanos < 0 ? 0 : (System.nanoTime() - startNanos) / 1_000_000;
        ExperimentEvent event = new ExperimentEvent(experimentId, ++sequence, d.transactionId(), d.workerId(),
                d.threadName(), d.instanceId() != null ? d.instanceId() : defaultInstanceId, correlationId,
                d.connectionPid(), d.eventType(), d.resource(), clock.instant(), elapsed, d.source(), d.message(),
                d.details() == null ? Map.of() : d.details());
        events.add(event);
        store.append(event);
        for (EventSubscriber s : subscribers) {
            s.onEvent(event);
        }
        return event;
    }

    /** Closes the bus after the terminal event: subscribers receive the end signal. */
    public synchronized void close(ExperimentStatus status) {
        if (endStatus != null) {
            return;
        }
        endStatus = status;
        closedAt = clock.instant();
        for (EventSubscriber s : subscribers) {
            s.onEnd(status);
        }
        subscribers.clear();
    }

    /** Replays every event with {@code sequence > afterSequence} and then attaches the subscriber atomically. */
    public synchronized void subscribe(long afterSequence, EventSubscriber subscriber) {
        for (ExperimentEvent e : events) {
            if (e.sequence() > afterSequence) {
                subscriber.onEvent(e);
            }
        }
        if (endStatus != null) {
            subscriber.onEnd(endStatus);
        } else {
            subscribers.add(subscriber);
        }
    }

    public void unsubscribe(EventSubscriber subscriber) {
        subscribers.remove(subscriber);
    }

    public synchronized List<ExperimentEvent> snapshot() {
        return List.copyOf(events);
    }

    public synchronized long lastSequence() {
        return sequence;
    }

    public synchronized int size() {
        return events.size();
    }

    public int dropped() {
        return dropped;
    }

    public boolean isClosed() {
        return endStatus != null;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public int subscriberCount() {
        return subscribers.size();
    }
}

package dev.racelab.infrastructure.web.sse;

import dev.racelab.application.engine.ExperimentEventPublisher;
import dev.racelab.application.engine.LiveEventBus;
import dev.racelab.application.exception.ApiException;
import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.model.ExperimentRecord;
import dev.racelab.domain.port.EventStore;
import dev.racelab.domain.port.ExperimentRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE streams of experiment events: replay from {@code Last-Event-ID}, then live events. When the
 * experiment runs on another backend instance (or this instance restarted), the stream tails the durable
 * event log instead of the in-memory bus.
 */
@Component
public class EventStreamService {

    private static final long TIMEOUT_MS = Duration.ofHours(1).toMillis();

    private final ExperimentEventPublisher publisher;
    private final EventStore store;
    private final ExperimentRepository repository;
    private final Set<SseSubscriber> active = ConcurrentHashMap.newKeySet();

    public EventStreamService(ExperimentEventPublisher publisher, EventStore store, ExperimentRepository repository) {
        this.publisher = publisher;
        this.store = store;
        this.repository = repository;
    }

    public SseEmitter open(String experimentId, long lastEventId) {
        repository.findById(experimentId).orElseThrow(() -> ApiException.notFound("Experiment " + experimentId));
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Optional<LiveEventBus> bus = publisher.bus(experimentId);
        SseSubscriber[] holder = new SseSubscriber[1];
        SseSubscriber subscriber = new SseSubscriber(emitter, lastEventId, () -> {
            active.remove(holder[0]);
            bus.ifPresent(b -> b.unsubscribe(holder[0]));
        });
        holder[0] = subscriber;
        active.add(subscriber);
        emitter.onCompletion(subscriber::close);
        emitter.onTimeout(subscriber::close);
        emitter.onError(e -> subscriber.close());
        if (bus.isPresent()) {
            bus.get().subscribe(lastEventId, subscriber);
        } else {
            Thread.ofVirtual().name("racelab-sse-tail-" + experimentId).start(() -> tail(experimentId, subscriber));
        }
        return emitter;
    }

    private void tail(String experimentId, SseSubscriber subscriber) {
        while (!subscriber.isClosed()) {
            List<ExperimentEvent> events = store.findAfter(experimentId, subscriber.lastSequence(), 500);
            events.forEach(subscriber::onEvent);
            if (events.isEmpty()) {
                Optional<ExperimentRecord> r = repository.findById(experimentId);
                if (r.isEmpty()) {
                    subscriber.close();
                    return;
                }
                if (r.get().status().isTerminal()) {
                    List<ExperimentEvent> rest = store.findAfter(experimentId, subscriber.lastSequence(), 5000);
                    rest.forEach(subscriber::onEvent);
                    subscriber.onEnd(r.get().status());
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Scheduled(fixedRate = 15_000)
    void heartbeat() {
        active.forEach(SseSubscriber::heartbeat);
    }

    public int activeStreams() {
        return active.size();
    }
}

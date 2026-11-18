package dev.racelab.infrastructure.web.sse;

import dev.racelab.application.engine.EventSubscriber;
import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.model.ExperimentStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * One SSE connection. Events are queued without blocking the publisher (worker threads never wait for a
 * slow browser) and sent one by one, each as its own SSE frame with {@code id: <sequence>}.
 */
final class SseSubscriber implements EventSubscriber {

    private sealed interface Frame permits EventFrame, EndFrame, HeartbeatFrame {
    }

    private record EventFrame(ExperimentEvent event) implements Frame {
    }

    private record EndFrame(ExperimentStatus status) implements Frame {
    }

    private record HeartbeatFrame() implements Frame {
    }

    private final SseEmitter emitter;
    private final BlockingQueue<Frame> queue = new LinkedBlockingQueue<>();
    private final Runnable onClose;
    private volatile boolean closed;
    private volatile long lastSequence;

    SseSubscriber(SseEmitter emitter, long lastSequence, Runnable onClose) {
        this.emitter = emitter;
        this.lastSequence = lastSequence;
        this.onClose = onClose;
        Thread.ofVirtual().name("racelab-sse-sender").start(this::loop);
    }

    long lastSequence() {
        return lastSequence;
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void onEvent(ExperimentEvent event) {
        if (!closed && event.sequence() > lastSequence) {
            lastSequence = event.sequence();
            queue.offer(new EventFrame(event));
        }
    }

    @Override
    public void onEnd(ExperimentStatus status) {
        if (!closed) {
            queue.offer(new EndFrame(status));
        }
    }

    void heartbeat() {
        if (!closed) {
            queue.offer(new HeartbeatFrame());
        }
    }

    private void loop() {
        try {
            while (!closed) {
                Frame frame = queue.take();
                switch (frame) {
                    case EventFrame(ExperimentEvent e) -> emitter.send(SseEmitter.event()
                            .id(Long.toString(e.sequence())).name("experiment-event").data(e, MediaType.APPLICATION_JSON));
                    case HeartbeatFrame() -> emitter.send(SseEmitter.event().name("heartbeat")
                            .data(Map.of("timestamp", Instant.now().toString()), MediaType.APPLICATION_JSON));
                    case EndFrame(ExperimentStatus status) -> {
                        emitter.send(SseEmitter.event().name("end").data(Map.of("status", status.name()),
                                MediaType.APPLICATION_JSON));
                        emitter.complete();
                        close();
                    }
                }
            }
        } catch (Exception e) {
            // client disconnected or interrupted
            close();
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // already completed
            }
        }
    }

    void close() {
        if (!closed) {
            closed = true;
            onClose.run();
        }
    }
}

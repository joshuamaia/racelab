package dev.racelab.application.port;

import java.util.List;
import java.util.Map;

/** Minimal message broker used by the outbox/messaging experiments (Redis Streams, or in-memory fallback). */
public interface MessageBroker {

    String name();

    /** @return broker-assigned message id */
    String publish(String topic, Map<String, String> message);

    /** Messages published after {@code afterId} ("0" for the beginning), in order. */
    List<Message> read(String topic, String afterId, int max);

    void deleteTopic(String topic);

    record Message(String id, Map<String, String> body) {
    }
}

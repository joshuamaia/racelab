package dev.racelab.infrastructure.redis;

import dev.racelab.application.port.DistributedLockService;
import dev.racelab.application.port.MessageBroker;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes to Redis Streams when Redis is reachable, otherwise to an in-memory log. The choice is made once
 * per topic, so a single experiment never mixes both.
 */
@Component
public class RoutingMessageBroker implements MessageBroker {

    private final StringRedisTemplate redis;
    private final DistributedLockService redisHealth;
    private final Map<String, Boolean> topicUsesRedis = new ConcurrentHashMap<>();
    private final Map<String, List<Message>> memory = new ConcurrentHashMap<>();
    private final AtomicLong memorySeq = new AtomicLong();

    public RoutingMessageBroker(StringRedisTemplate redis, DistributedLockService redisHealth) {
        this.redis = redis;
        this.redisHealth = redisHealth;
    }

    @Override
    public String name() {
        return redisHealth.isAvailable() ? "redis-streams" : "in-memory";
    }

    private boolean useRedis(String topic) {
        return topicUsesRedis.computeIfAbsent(topic, t -> redisHealth.isAvailable());
    }

    @Override
    public String publish(String topic, Map<String, String> message) {
        if (useRedis(topic)) {
            MapRecord<String, String, String> record = StreamRecords.string(message).withStreamKey(topic);
            RecordId id = redis.opsForStream().add(record);
            redis.expire(topic, java.time.Duration.ofHours(6));
            return id == null ? null : id.getValue();
        }
        String id = memorySeq.incrementAndGet() + "-0";
        memory.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(new Message(id, Map.copyOf(message)));
        return id;
    }

    @Override
    public List<Message> read(String topic, String afterId, int max) {
        List<Message> out = new ArrayList<>();
        if (useRedis(topic)) {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(topic,
                    org.springframework.data.domain.Range.unbounded());
            if (records != null) {
                for (MapRecord<String, Object, Object> r : records) {
                    if (compare(r.getId().getValue(), afterId) > 0 && out.size() < max) {
                        Map<String, String> body = new java.util.LinkedHashMap<>();
                        r.getValue().forEach((k, v) -> body.put(String.valueOf(k), String.valueOf(v)));
                        out.add(new Message(r.getId().getValue(), body));
                    }
                }
            }
            return out;
        }
        for (Message m : memory.getOrDefault(topic, List.of())) {
            if (compare(m.id(), afterId) > 0 && out.size() < max) {
                out.add(m);
            }
        }
        return out;
    }

    @Override
    public void deleteTopic(String topic) {
        if (Boolean.TRUE.equals(topicUsesRedis.remove(topic))) {
            redis.delete(topic);
        }
        memory.remove(topic);
    }

    /** Compares stream ids of the form {@code <millis>-<seq>}. */
    static int compare(String a, String b) {
        String[] x = a.split("-");
        String[] y = b.split("-");
        int c = Long.compare(Long.parseLong(x[0]), Long.parseLong(y[0]));
        if (c != 0) {
            return c;
        }
        return Long.compare(x.length > 1 ? Long.parseLong(x[1]) : 0, y.length > 1 ? Long.parseLong(y[1]) : 0);
    }
}

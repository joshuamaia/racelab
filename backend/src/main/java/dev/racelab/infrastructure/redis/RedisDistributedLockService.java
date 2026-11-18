package dev.racelab.infrastructure.redis;

import dev.racelab.application.port.DistributedLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Lease-based lock on Redis. Acquisition is {@code SET key owner NX PX ttl}; release is an atomic
 * compare-and-delete script so a client whose lease expired can never delete the lock of the new owner.
 * A separate {@code INCR} counter provides monotonically increasing fencing tokens.
 * <p>
 * A Redis lock alone does not guarantee mutual exclusion under process pauses, clock jumps or failover:
 * the lock-expiration experiment demonstrates it, and fencing tokens are the complementary defence.
 */
@Component
public class RedisDistributedLockService implements DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLockService.class);

    // @snippet:start bank-withdrawal REDIS_LOCK java "Safe release: compare-and-delete in Lua"
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """, Long.class);
    // @snippet:end

    private final StringRedisTemplate redis;
    private volatile long checkedAt;
    private volatile boolean available;

    public RedisDistributedLockService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now - checkedAt > 2_000) {
            try {
                String pong = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
                available = "PONG".equalsIgnoreCase(pong);
            } catch (RuntimeException e) {
                if (available) {
                    log.warn("Redis became unavailable: {}", e.getMessage());
                }
                available = false;
            }
            checkedAt = now;
        }
        return available;
    }

    @Override
    public Optional<Lease> tryAcquire(String key, String owner, Duration ttl) {
        Boolean ok = redis.opsForValue().setIfAbsent(key, owner, ttl);
        if (!Boolean.TRUE.equals(ok)) {
            return Optional.empty();
        }
        Long token = redis.opsForValue().increment("racelab:fence:" + key);
        redis.expire("racelab:fence:" + key, Duration.ofHours(1));
        return Optional.of(new Lease(key, owner, token == null ? 0 : token, ttl));
    }

    @Override
    public boolean release(Lease lease) {
        Long deleted = redis.execute(RELEASE, List.of(lease.key()), lease.owner());
        return deleted != null && deleted > 0;
    }

    @Override
    public Optional<String> currentOwner(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }
}

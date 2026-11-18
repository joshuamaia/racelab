package dev.racelab.application.port;

import java.time.Duration;
import java.util.Optional;

/** Lease-based distributed lock (implemented with Redis). */
public interface DistributedLockService {

    boolean isAvailable();

    /**
     * Tries once to acquire the lock ({@code SET key owner NX PX ttl}).
     *
     * @return the lease when acquired, including a monotonically increasing fencing token
     */
    Optional<Lease> tryAcquire(String key, String owner, Duration ttl);

    /** Releases only if the caller still owns the lock (atomic compare-and-delete). */
    boolean release(Lease lease);

    /** Current owner of the key, if any. */
    Optional<String> currentOwner(String key);

    record Lease(String key, String owner, long fencingToken, Duration ttl) {
    }
}

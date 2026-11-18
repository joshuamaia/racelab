package dev.racelab.scenario.support;

import dev.racelab.application.engine.BlockRegistry;
import dev.racelab.application.engine.ScenarioExceptions.LockNotAcquiredException;
import dev.racelab.application.engine.TxGuard;
import dev.racelab.application.engine.WorkerRuntime;
import dev.racelab.application.port.DistributedLockService;
import dev.racelab.application.port.DistributedLockService.Lease;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Locks that wrap a whole transaction attempt. JVM locks live in static maps: they are shared by every
 * thread of THIS JVM and invisible to any other backend instance — which is exactly what the distributed
 * experiments demonstrate.
 */
public final class LockGuards {

    private static final ConcurrentMap<String, Object> MONITORS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private LockGuards() {
    }

    // @snippet:start bank-withdrawal JAVA_SYNCHRONIZED java "synchronized around the whole transaction (JVM-local)"
    public static TxGuard synchronizedGuard(WorkerRuntime rt, String key, String resource) {
        return new TxGuard() {
            @Override
            public <T> T around(GuardScope scope, Supplier<T> attempt) {
                Object monitor = MONITORS.computeIfAbsent(key, k -> new Object());
                scope.lockRequested(resource, "synchronized(" + key + ") in JVM " + rt.instanceId(), "JVM");
                long start = System.nanoTime();
                rt.block(BlockRegistry.Kind.JVM_MONITOR, "synchronized(" + key + ")", null);
                synchronized (monitor) {
                    rt.unblock();
                    scope.lockAcquired(resource, "synchronized(" + key + ") in JVM " + rt.instanceId(), "JVM",
                            (System.nanoTime() - start) / 1_000_000);
                    try {
                        return attempt.get(); // BEGIN … COMMIT happens inside the monitor
                    } finally {
                        scope.lockReleased(resource, "monitor released");
                    }
                }
            }
        };
    }
    // @snippet:end

    // @snippet:start bank-withdrawal REENTRANT_LOCK java "ReentrantLock: tryLock with timeout, unlock in finally"
    public static TxGuard reentrantGuard(WorkerRuntime rt, String key, String resource) {
        return new TxGuard() {
            @Override
            public <T> T around(GuardScope scope, Supplier<T> attempt) {
                ReentrantLock lock = LOCKS.computeIfAbsent(key, k -> new ReentrantLock(true));
                scope.lockRequested(resource, "ReentrantLock(" + key + ").tryLock() in JVM " + rt.instanceId(), "JVM");
                long start = System.nanoTime();
                boolean acquired = lock.tryLock();
                if (!acquired) {
                    scope.lockWaiting(resource, "Lock held by another thread (queue length " + lock.getQueueLength()
                            + ") — tryLock(" + rt.config().lockTimeoutMs() + " ms)");
                    rt.block(BlockRegistry.Kind.JVM, "ReentrantLock(" + key + ")", null);
                    try {
                        acquired = lock.tryLock(rt.config().lockTimeoutMs(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        rt.unblock();
                    }
                }
                if (!acquired) {
                    throw new LockNotAcquiredException("ReentrantLock not acquired within " + rt.config().lockTimeoutMs() + " ms");
                }
                try {
                    scope.lockAcquired(resource, "ReentrantLock(" + key + ")", "JVM", (System.nanoTime() - start) / 1_000_000);
                    return attempt.get();
                } finally {
                    lock.unlock();
                    scope.lockReleased(resource, "unlock() in finally");
                }
            }
        };
    }
    // @snippet:end

    // @snippet:start bank-withdrawal REDIS_LOCK java "Redis lock: SET NX PX ttl, owner token, safe release"
    public static TxGuard redisGuard(WorkerRuntime rt, DistributedLockService locks, String key, String resource) {
        return new TxGuard() {
            @Override
            public <T> T around(GuardScope scope, Supplier<T> attempt) {
                String owner = scope.transactionId() + ":" + UUID.randomUUID();
                Duration ttl = Duration.ofMillis(rt.config().lockTtlMs());
                scope.lockRequested(resource, "SET " + key + " <owner> NX PX " + ttl.toMillis(), "REDIS");
                long start = System.nanoTime();
                long deadline = System.currentTimeMillis() + rt.config().lockTimeoutMs();
                Optional<Lease> lease = locks.tryAcquire(key, owner, ttl);
                if (lease.isEmpty()) {
                    scope.lockWaiting(resource, "Redis lock owned by " + locks.currentOwner(key).orElse("?") + " — polling");
                    rt.block(BlockRegistry.Kind.REDIS, "Redis lock " + key, null);
                    try {
                        while (lease.isEmpty() && System.currentTimeMillis() < deadline) {
                            rt.pause(20);
                            lease = locks.tryAcquire(key, owner, ttl);
                        }
                    } finally {
                        rt.unblock();
                    }
                }
                Lease acquired = lease.orElseThrow(() ->
                        new LockNotAcquiredException("Redis lock " + key + " not acquired within " + rt.config().lockTimeoutMs() + " ms"));
                scope.lockAcquired(resource, "Redis lock " + key + " (fencing token " + acquired.fencingToken() + ")",
                        "REDIS", (System.nanoTime() - start) / 1_000_000);
                try {
                    return attempt.get();
                } finally {
                    boolean released = locks.release(acquired); // compare-and-delete: never deletes someone else's lock
                    scope.lockReleased(resource, released ? "Redis lock released by its owner"
                            : "Lease had already EXPIRED — the lock was no longer ours (not deleted)");
                }
            }
        };
    }
    // @snippet:end
}

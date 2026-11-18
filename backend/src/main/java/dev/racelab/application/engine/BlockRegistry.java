package dev.racelab.application.engine;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which workers are (possibly) waiting for a lock. Database waits are only considered real once the
 * {@link LockWaitObserver} confirms them through {@code pg_blocking_pids}; JVM and Redis waits are known by
 * the code that performs them.
 */
public final class BlockRegistry {

    /** Grace period after which an unconfirmed database wait is assumed (the observer may be lagging). */
    static final long UNCONFIRMED_GRACE_MS = 1500;

    /** JVM_MONITOR: about to enter a synchronized block — contention cannot be probed, so it only counts once it persists. */
    public enum Kind { DATABASE, JVM, JVM_MONITOR, REDIS }

    /** Grace period for an unconfirmed monitor wait (an uncontended monitor is entered in microseconds). */
    static final long MONITOR_GRACE_MS = 250;

    public static final class Block {
        final String workerId;
        final Kind kind;
        final String reason;
        final Long pid;
        final long sinceNanos = System.nanoTime();
        volatile boolean confirmed;
        volatile long confirmedAtNanos;
        volatile List<Long> blockedBy = List.of();
        volatile String waitEvent;

        Block(String workerId, Kind kind, String reason, Long pid) {
            this.workerId = workerId;
            this.kind = kind;
            this.reason = reason;
            this.pid = pid;
            this.confirmed = kind != Kind.DATABASE && kind != Kind.JVM_MONITOR;
        }

        public String workerId() {
            return workerId;
        }

        public Kind kind() {
            return kind;
        }

        public String reason() {
            return reason;
        }

        public Long pid() {
            return pid;
        }

        public boolean confirmed() {
            return confirmed;
        }

        public List<Long> blockedBy() {
            return blockedBy;
        }

        public String waitEvent() {
            return waitEvent;
        }

        boolean countsAsBlocked() {
            long grace = kind == Kind.JVM_MONITOR ? MONITOR_GRACE_MS : UNCONFIRMED_GRACE_MS;
            return confirmed || (System.nanoTime() - sinceNanos) / 1_000_000 > grace;
        }

        /** Milliseconds this worker was observed waiting (0 when the wait was never confirmed). */
        long confirmedWaitMs() {
            if (kind != Kind.DATABASE && kind != Kind.JVM_MONITOR) {
                return (System.nanoTime() - sinceNanos) / 1_000_000;
            }
            return confirmed ? (System.nanoTime() - sinceNanos) / 1_000_000 : 0;
        }
    }

    private final Map<String, Block> blocks = new ConcurrentHashMap<>();
    private final Runnable onChange;

    public BlockRegistry(Runnable onChange) {
        this.onChange = onChange;
    }

    public void block(String workerId, Kind kind, String reason, Long pid) {
        blocks.put(workerId, new Block(workerId, kind, reason, pid));
        onChange.run();
    }

    /** @return the observed wait duration in milliseconds */
    public long unblock(String workerId) {
        Block b = blocks.remove(workerId);
        onChange.run();
        return b == null ? 0 : b.confirmedWaitMs();
    }

    public boolean isBlocked(String workerId) {
        Block b = blocks.get(workerId);
        return b != null && b.countsAsBlocked();
    }

    public Optional<Block> get(String workerId) {
        return Optional.ofNullable(blocks.get(workerId));
    }

    public List<Block> databaseBlocks() {
        return blocks.values().stream().filter(b -> b.kind == Kind.DATABASE && b.pid != null).toList();
    }

    public List<Block> all() {
        return List.copyOf(blocks.values());
    }

    void confirm(Block b, List<Long> blockedBy, String waitEvent) {
        b.blockedBy = blockedBy;
        b.waitEvent = waitEvent;
        if (!b.confirmed) {
            b.confirmed = true;
            b.confirmedAtNanos = System.nanoTime();
            onChange.run();
        }
    }
}

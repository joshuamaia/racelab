package dev.racelab.application.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Deterministic coordination point for the critical section of an experiment (e.g. "every transaction has
 * read the balance before anyone writes"). A worker waiting at a point is released when every other
 * participant has either arrived at the same point, finished, or is <em>really</em> blocked on a lock
 * (confirmed by PostgreSQL through {@code pg_blocking_pids}, or by the JVM/Redis lock code).
 * <p>
 * This replaces {@code Thread.sleep}-based race reproduction: a plain {@link java.util.concurrent.CyclicBarrier}
 * would deadlock as soon as a strategy makes one transaction wait for another (pessimistic locking),
 * while this barrier lets the lock owner proceed exactly when the contender is parked on the lock.
 */
public final class Rendezvous {

    private final Set<String> participants;
    private final Map<String, Set<String>> arrivals = new HashMap<>();
    private final Set<String> finished = new HashSet<>();
    private final Predicate<String> isBlocked;

    public Rendezvous(List<String> participants, Predicate<String> isBlocked) {
        this.participants = Set.copyOf(participants);
        this.isBlocked = isBlocked;
    }

    /**
     * @return {@code true} when every participant was accounted for, {@code false} on timeout or cancellation
     */
    public synchronized boolean await(String point, String workerId, long timeoutMs, BooleanSupplier cancelled) {
        arrivals.computeIfAbsent(point, p -> new HashSet<>()).add(workerId);
        notifyAll();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!satisfied(point, workerId)) {
            if (cancelled.getAsBoolean()) {
                return false;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }
            try {
                wait(Math.min(remaining, 20));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    public synchronized void finished(String workerId) {
        finished.add(workerId);
        notifyAll();
    }

    public synchronized void wake() {
        notifyAll();
    }

    private boolean satisfied(String point, String self) {
        Set<String> arrived = arrivals.getOrDefault(point, Set.of());
        for (String p : participants) {
            if (p.equals(self) || arrived.contains(p) || finished.contains(p) || isBlocked.test(p)) {
                continue;
            }
            return false;
        }
        return true;
    }
}

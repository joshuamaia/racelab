package dev.racelab.scenario.isolation;

import dev.racelab.application.engine.WorkerRuntime;

/**
 * Strict interleaving between two transactions for the isolation probes: step N runs only after step N-1
 * finished, regardless of which worker owns it. Isolation reads never block in PostgreSQL (MVCC), so a
 * total order is safe here.
 */
final class TurnSequencer {

    private final long timeoutMs;
    private int next = 1;

    TurnSequencer(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    synchronized void await(int turn, WorkerRuntime rt) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (next < turn) {
            rt.throwIfCancelled();
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new IllegalStateException("Turn " + turn + " not reached within " + timeoutMs + " ms");
            }
            try {
                wait(Math.min(remaining, 50));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for turn " + turn);
            }
        }
    }

    synchronized void done(int turn) {
        if (next <= turn) {
            next = turn + 1;
        }
        notifyAll();
    }

    <T> T step(int turn, WorkerRuntime rt, java.util.function.Supplier<T> action) {
        await(turn, rt);
        try {
            return action.get();
        } finally {
            done(turn);
        }
    }
}

package dev.racelab.application.engine;

import dev.racelab.domain.model.StepState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Step-by-step execution. In step mode each worker parks at every checkpoint (begin, read, validate, write,
 * commit) until the user releases it. If nobody interacts for {@code stepTimeout}, the experiment is
 * cancelled so no transaction stays open waiting for a human.
 */
public final class StepController {

    private final Clock clock;
    private final Duration timeout;
    private final CancellationToken token;
    private final Consumer<String> onTimeout;
    private final Map<String, StepState.Pending> pending = new LinkedHashMap<>();
    private volatile boolean active;
    private Instant lastInteraction;

    public StepController(boolean active, Duration timeout, Clock clock, CancellationToken token,
                          Consumer<String> onTimeout) {
        this.active = active;
        this.timeout = timeout;
        this.clock = clock;
        this.token = token;
        this.onTimeout = onTimeout;
        this.lastInteraction = clock.instant();
        token.onCancel(this::wakeAll);
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Parks the calling worker at a checkpoint until released.
     *
     * @param onReached invoked once the checkpoint is registered (publishes CHECKPOINT_REACHED)
     * @return true if the worker actually parked (so the caller publishes CHECKPOINT_RELEASED)
     */
    public boolean reach(String workerId, String step, String description, Runnable onReached) {
        if (!active) {
            return false;
        }
        synchronized (this) {
            pending.put(workerId, new StepState.Pending(workerId, step, description, clock.instant()));
            notifyAll();
        }
        onReached.run();
        synchronized (this) {
            while (active && pending.containsKey(workerId) && !token.isCancelled()) {
                if (clock.instant().isAfter(deadline())) {
                    pending.remove(workerId);
                    onTimeout.accept("No step interaction for " + timeout.toSeconds() + "s");
                    break;
                }
                try {
                    wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            pending.remove(workerId);
        }
        token.throwIfCancelled();
        return true;
    }

    /** Releases the given worker, or the one waiting the longest when {@code workerId} is null. */
    public synchronized String release(String workerId) {
        if (!active) {
            throw new IllegalStateException("Experiment is not in step-by-step mode");
        }
        String target = workerId;
        if (target == null) {
            target = pending.keySet().stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("No worker is waiting at a checkpoint"));
        } else if (!pending.containsKey(target)) {
            throw new IllegalStateException(target + " is not waiting at a checkpoint");
        }
        pending.remove(target);
        lastInteraction = clock.instant();
        notifyAll();
        return target;
    }

    /** Leaves step mode and releases every parked worker. */
    public synchronized void resume() {
        active = false;
        pending.clear();
        lastInteraction = clock.instant();
        notifyAll();
    }

    public synchronized void touch() {
        lastInteraction = clock.instant();
    }

    public synchronized boolean hasPending() {
        return !pending.isEmpty();
    }

    public synchronized StepState snapshot(List<StepState.Blocked> blocked) {
        List<StepState.Pending> list = new ArrayList<>(pending.values());
        String suggested = list.isEmpty() ? null : list.getFirst().workerId();
        return new StepState(active, list, blocked, suggested, active ? deadline() : null);
    }

    private Instant deadline() {
        return lastInteraction.plus(timeout);
    }

    private synchronized void wakeAll() {
        notifyAll();
    }
}

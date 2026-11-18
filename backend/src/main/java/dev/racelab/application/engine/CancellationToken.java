package dev.racelab.application.engine;

import dev.racelab.application.engine.ScenarioExceptions.ExperimentCancelledException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Cooperative cancellation flag shared by every worker of an experiment. */
public final class CancellationToken {

    private volatile boolean cancelled;
    private volatile String reason;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled;
    }

    public String reason() {
        return reason;
    }

    public void onCancel(Runnable listener) {
        listeners.add(listener);
        if (cancelled) {
            listener.run();
        }
    }

    /** @return true when this call performed the cancellation */
    public boolean cancel(String why) {
        synchronized (this) {
            if (cancelled) {
                return false;
            }
            reason = why;
            cancelled = true;
        }
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException ignored) {
                // a failing listener must not prevent the others from releasing resources
            }
        }
        return true;
    }

    public void throwIfCancelled() {
        if (cancelled) {
            throw new ExperimentCancelledException("Experiment cancelled: " + reason);
        }
    }
}

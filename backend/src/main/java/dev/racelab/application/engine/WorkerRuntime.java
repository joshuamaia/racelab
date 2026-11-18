package dev.racelab.application.engine;

import dev.racelab.application.engine.ScenarioExceptions.ExperimentCancelledException;
import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.StrategyType;

import java.util.Map;

/**
 * Everything a worker needs from the experiment that owns it. The local implementation talks to the
 * in-process {@link ExperimentContext}; the remote implementation (distributed topology) forwards the same
 * calls over HTTP to the orchestrating backend instance, so the very same worker code runs in another JVM.
 */
public interface WorkerRuntime {

    String experimentId();

    String workerId();

    int workerIndex();

    String instanceId();

    ExperimentConfiguration config();

    StrategyType strategy();

    void emit(String transactionId, Long pid, EventType type, EventSource source, String resource, String message,
              Map<String, Object> details);

    /** Step-by-step checkpoint (no-op in concurrent mode). */
    void checkpoint(String step, String description);

    /** Coordinated interleaving point (no-op in step mode or when coordination is disabled). */
    void rendezvous(String point);

    void block(BlockRegistry.Kind kind, String reason, Long pid);

    /** @return observed wait in milliseconds */
    long unblock();

    void registerPid(long pid);

    void unregisterPid(long pid);

    void reportDeadlock(long victimPid, String detail);

    boolean isCancelled();

    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new ExperimentCancelledException("Experiment cancelled");
        }
    }

    /** Cooperative sleep: wakes up early when the experiment is cancelled. */
    default void pause(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (true) {
            throwIfCancelled();
            long remaining = end - System.currentTimeMillis();
            if (remaining <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(remaining, 50));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExperimentCancelledException("Interrupted");
            }
        }
    }

    default String shortId() {
        return "w" + (workerIndex() + 1);
    }
}

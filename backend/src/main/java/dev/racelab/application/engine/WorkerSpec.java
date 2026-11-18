package dev.racelab.application.engine;

import java.util.List;
import java.util.Map;

/**
 * @param remote      execute on the peer JVM (distributed topology)
 * @param operations  1-based operation numbers assigned to this worker (used by remote execution)
 * @param remoteData  scenario data the remote worker needs (resource ids…)
 */
public record WorkerSpec(String workerId, int index, WorkerTask task, boolean remote, List<Integer> operations,
                         Map<String, Object> remoteData) {

    public static WorkerSpec local(int index, WorkerTask task) {
        return new WorkerSpec("worker-" + (index + 1), index, task, false, List.of(), Map.of());
    }

    public static WorkerSpec local(String workerId, int index, WorkerTask task) {
        return new WorkerSpec(workerId, index, task, false, List.of(), Map.of());
    }
}

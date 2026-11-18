package dev.racelab.infrastructure.remote;

import dev.racelab.application.engine.BlockRegistry;
import dev.racelab.application.engine.WorkerRuntime;
import dev.racelab.application.port.RemoteWorkerGateway.RemoteWorkerRequest;
import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.StrategyType;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Runtime of a worker executing in THIS JVM on behalf of an experiment orchestrated by another instance.
 * Events, checkpoints, rendezvous and lock-wait declarations are forwarded to the orchestrator, so the
 * timeline, the coordination and the cancellation stay global while the locks (synchronized,
 * ReentrantLock) are genuinely local to this JVM.
 */
final class RemoteWorkerRuntime implements WorkerRuntime {

    private final RemoteWorkerRequest request;
    private final RestClient client;
    private final String base;
    private final String instanceId;
    private volatile long cancelCheckedAt;
    private volatile boolean cancelled;

    RemoteWorkerRuntime(RemoteWorkerRequest request, RestClient client, String instanceId) {
        this.request = request;
        this.client = client;
        this.base = request.callbackBaseUrl() + "/internal/runtime/" + request.experimentId();
        this.instanceId = instanceId;
    }

    @Override
    public String experimentId() {
        return request.experimentId();
    }

    @Override
    public String workerId() {
        return request.workerId();
    }

    @Override
    public int workerIndex() {
        return request.workerIndex();
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public ExperimentConfiguration config() {
        return request.configuration();
    }

    @Override
    public StrategyType strategy() {
        return request.strategy();
    }

    @Override
    public void emit(String transactionId, Long pid, EventType type, EventSource source, String resource, String message,
                     Map<String, Object> details) {
        client.post().uri(base + "/emit").body(new InternalApi.EmitRequest(workerId(), Thread.currentThread().getName(),
                instanceId, transactionId, pid, type, source, resource, message, details)).retrieve().toBodilessEntity();
    }

    @Override
    public void checkpoint(String step, String description) {
        InternalApi.StatusResponse r = client.post().uri(base + "/checkpoint")
                .body(new InternalApi.CheckpointRequest(workerId(), workerIndex(), instanceId, step, description))
                .retrieve().body(InternalApi.StatusResponse.class);
        cancelled = r != null && r.cancelled();
        throwIfCancelled();
    }

    @Override
    public void rendezvous(String point) {
        InternalApi.StatusResponse r = client.post().uri(base + "/rendezvous")
                .body(new InternalApi.RendezvousRequest(workerId(), workerIndex(), instanceId, point))
                .retrieve().body(InternalApi.StatusResponse.class);
        cancelled = r != null && r.cancelled();
        throwIfCancelled();
    }

    @Override
    public void block(BlockRegistry.Kind kind, String reason, Long pid) {
        client.post().uri(base + "/block").body(new InternalApi.BlockRequest(workerId(), kind, reason, pid))
                .retrieve().toBodilessEntity();
    }

    @Override
    public long unblock() {
        InternalApi.StatusResponse r = client.post().uri(base + "/unblock")
                .body(new InternalApi.BlockRequest(workerId(), null, null, null))
                .retrieve().body(InternalApi.StatusResponse.class);
        return r == null ? 0 : r.value();
    }

    @Override
    public void registerPid(long pid) {
        client.post().uri(base + "/pid").body(new InternalApi.PidRequest(workerId(), pid, true)).retrieve().toBodilessEntity();
    }

    @Override
    public void unregisterPid(long pid) {
        client.post().uri(base + "/pid").body(new InternalApi.PidRequest(workerId(), pid, false)).retrieve().toBodilessEntity();
    }

    @Override
    public void reportDeadlock(long victimPid, String detail) {
        client.post().uri(base + "/deadlock").body(new InternalApi.DeadlockRequest(workerId(), victimPid, detail))
                .retrieve().toBodilessEntity();
    }

    @Override
    public boolean isCancelled() {
        long now = System.currentTimeMillis();
        if (!cancelled && now - cancelCheckedAt > 100) {
            InternalApi.StatusResponse r = client.get().uri(base + "/status").retrieve().body(InternalApi.StatusResponse.class);
            cancelled = r == null || r.cancelled();
            cancelCheckedAt = now;
        }
        return cancelled;
    }
}

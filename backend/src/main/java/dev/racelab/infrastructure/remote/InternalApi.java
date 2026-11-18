package dev.racelab.infrastructure.remote;

import dev.racelab.application.engine.BlockRegistry;
import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;

import java.util.Map;

/** Payloads of the internal (instance-to-instance) protocol used by the distributed topology. */
public final class InternalApi {

    public static final String TOKEN_HEADER = "X-RaceLab-Internal-Token";

    private InternalApi() {
    }

    public record EmitRequest(String workerId, String threadName, String instanceId, String transactionId, Long pid,
                              EventType type, EventSource source, String resource, String message,
                              Map<String, Object> details) {
    }

    public record CheckpointRequest(String workerId, int workerIndex, String instanceId, String step, String description) {
    }

    public record RendezvousRequest(String workerId, int workerIndex, String instanceId, String point) {
    }

    public record BlockRequest(String workerId, BlockRegistry.Kind kind, String reason, Long pid) {
    }

    public record PidRequest(String workerId, long pid, boolean register) {
    }

    public record DeadlockRequest(String workerId, long victimPid, String detail) {
    }

    public record StatusResponse(boolean cancelled, long value) {
    }
}

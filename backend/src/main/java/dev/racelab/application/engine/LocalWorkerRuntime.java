package dev.racelab.application.engine;

import dev.racelab.domain.model.EventDraft;
import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.StrategyType;

import java.util.Map;

/** {@link WorkerRuntime} backed by the in-process {@link ExperimentContext}. */
final class LocalWorkerRuntime implements WorkerRuntime {

    private final ExperimentContext ctx;
    private final String workerId;
    private final int index;
    private final String instanceId;
    private final String threadName;

    LocalWorkerRuntime(ExperimentContext ctx, String workerId, int index, String instanceId, String threadName) {
        this.ctx = ctx;
        this.workerId = workerId;
        this.index = index;
        this.instanceId = instanceId;
        this.threadName = threadName;
    }

    @Override
    public String experimentId() {
        return ctx.id();
    }

    @Override
    public String workerId() {
        return workerId;
    }

    @Override
    public int workerIndex() {
        return index;
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public ExperimentConfiguration config() {
        return ctx.config();
    }

    @Override
    public StrategyType strategy() {
        return ctx.strategy();
    }

    @Override
    public void emit(String transactionId, Long pid, EventType type, EventSource source, String resource,
                     String message, Map<String, Object> details) {
        ctx.publish(new EventDraft(transactionId, workerId, threadName != null ? threadName : Thread.currentThread().getName(),
                instanceId, pid, type, resource, source, message, details == null ? Map.of() : details));
    }

    @Override
    public void checkpoint(String step, String description) {
        ctx.checkpoint(this, step, description);
    }

    @Override
    public void rendezvous(String point) {
        ctx.rendezvous(this, point);
    }

    @Override
    public void block(BlockRegistry.Kind kind, String reason, Long pid) {
        ctx.blocks().block(workerId, kind, reason, pid);
    }

    @Override
    public long unblock() {
        return ctx.blocks().unblock(workerId);
    }

    @Override
    public void registerPid(long pid) {
        ctx.registerPid(workerId, pid);
    }

    @Override
    public void unregisterPid(long pid) {
        ctx.unregisterPid(pid);
    }

    @Override
    public void reportDeadlock(long victimPid, String detail) {
        ctx.reportDeadlock(workerId, victimPid, detail);
    }

    @Override
    public boolean isCancelled() {
        return ctx.token().isCancelled();
    }
}

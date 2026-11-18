package dev.racelab.infrastructure.remote;

import dev.racelab.application.engine.ExperimentContext;
import dev.racelab.application.engine.ExperimentExecutor;
import dev.racelab.application.engine.ExperimentRegistry;
import dev.racelab.application.engine.WorkerRuntime;
import dev.racelab.application.engine.WorkerTask;
import dev.racelab.application.exception.ApiException;
import dev.racelab.application.port.RemoteWorkerGateway.RemoteWorkerRequest;
import dev.racelab.domain.model.TransactionOutcome;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Instance-to-instance protocol (never exposed through the BFF/Nginx, and protected by a shared token).
 * <ul>
 *   <li>{@code /internal/workers/execute}: run a worker of a remote experiment in this JVM</li>
 *   <li>{@code /internal/runtime/{id}/…}: callbacks from remote workers to the orchestrating instance</li>
 * </ul>
 */
@Hidden
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final ExperimentExecutor executor;
    private final ExperimentRegistry registry;
    private final String token;
    private final String instanceId;
    private final RestClient callbackClient;

    public InternalController(ExperimentExecutor executor, ExperimentRegistry registry,
                              @Value("${racelab.internal-token}") String token,
                              @Value("${racelab.instance-id}") String instanceId) {
        this.executor = executor;
        this.registry = registry;
        this.token = token;
        this.instanceId = instanceId;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(15 * 60_000);
        this.callbackClient = RestClient.builder().requestFactory(factory)
                .defaultHeader(InternalApi.TOKEN_HEADER, token).build();
    }

    // ------------------------------------------------------------------ worker side

    @PostMapping("/workers/execute")
    public List<TransactionOutcome> execute(@RequestHeader(InternalApi.TOKEN_HEADER) String auth,
                                            @RequestBody RemoteWorkerRequest request) throws Exception {
        authorize(auth);
        WorkerTask task = registry.get(request.scenarioId()).remoteWorker(request)
                .orElseThrow(() -> ApiException.validation("Scenario " + request.scenarioId() + " has no remote worker"));
        Thread t = Thread.currentThread();
        String previous = t.getName();
        t.setName("racelab-" + request.experimentId() + "-" + request.workerId() + "@" + instanceId);
        MDC.put("experimentId", request.experimentId());
        MDC.put("workerId", request.workerId());
        try {
            WorkerRuntime rt = new RemoteWorkerRuntime(request, callbackClient, instanceId);
            return task.run(rt);
        } finally {
            t.setName(previous);
            MDC.remove("experimentId");
            MDC.remove("workerId");
        }
    }

    // ------------------------------------------------------------------ orchestrator side

    @PostMapping("/runtime/{id}/emit")
    public void emit(@RequestHeader(InternalApi.TOKEN_HEADER) String auth, @PathVariable String id,
                     @RequestBody InternalApi.EmitRequest r) {
        authorize(auth);
        runtime(id, r.workerId(), 0, r.instanceId(), r.threadName())
                .emit(r.transactionId(), r.pid(), r.type(), r.source(), r.resource(), r.message(), r.details());
    }

    @PostMapping("/runtime/{id}/checkpoint")
    public InternalApi.StatusResponse checkpoint(@RequestHeader(InternalApi.TOKEN_HEADER) String auth,
                                                 @PathVariable String id, @RequestBody InternalApi.CheckpointRequest r) {
        authorize(auth);
        ExperimentContext ctx = context(id);
        try {
            ctx.runtimeFor(r.workerId(), r.workerIndex(), r.instanceId(), null).checkpoint(r.step(), r.description());
        } catch (RuntimeException cancelled) {
            // reported through the status below
        }
        return new InternalApi.StatusResponse(ctx.token().isCancelled(), 0);
    }

    @PostMapping("/runtime/{id}/rendezvous")
    public InternalApi.StatusResponse rendezvous(@RequestHeader(InternalApi.TOKEN_HEADER) String auth,
                                                 @PathVariable String id, @RequestBody InternalApi.RendezvousRequest r) {
        authorize(auth);
        ExperimentContext ctx = context(id);
        try {
            ctx.runtimeFor(r.workerId(), r.workerIndex(), r.instanceId(), null).rendezvous(r.point());
        } catch (RuntimeException cancelled) {
            // reported through the status below
        }
        return new InternalApi.StatusResponse(ctx.token().isCancelled(), 0);
    }

    @PostMapping("/runtime/{id}/block")
    public void block(@RequestHeader(InternalApi.TOKEN_HEADER) String auth, @PathVariable String id,
                      @RequestBody InternalApi.BlockRequest r) {
        authorize(auth);
        context(id).blocks().block(r.workerId(), r.kind(), r.reason(), r.pid());
    }

    @PostMapping("/runtime/{id}/unblock")
    public InternalApi.StatusResponse unblock(@RequestHeader(InternalApi.TOKEN_HEADER) String auth,
                                              @PathVariable String id, @RequestBody InternalApi.BlockRequest r) {
        authorize(auth);
        ExperimentContext ctx = context(id);
        return new InternalApi.StatusResponse(ctx.token().isCancelled(), ctx.blocks().unblock(r.workerId()));
    }

    @PostMapping("/runtime/{id}/pid")
    public void pid(@RequestHeader(InternalApi.TOKEN_HEADER) String auth, @PathVariable String id,
                    @RequestBody InternalApi.PidRequest r) {
        authorize(auth);
        WorkerRuntime rt = context(id).runtimeFor(r.workerId(), 0, null, null);
        if (r.register()) {
            rt.registerPid(r.pid());
        } else {
            rt.unregisterPid(r.pid());
        }
    }

    @PostMapping("/runtime/{id}/deadlock")
    public void deadlock(@RequestHeader(InternalApi.TOKEN_HEADER) String auth, @PathVariable String id,
                         @RequestBody InternalApi.DeadlockRequest r) {
        authorize(auth);
        context(id).runtimeFor(r.workerId(), 0, null, null).reportDeadlock(r.victimPid(), r.detail());
    }

    @GetMapping("/runtime/{id}/status")
    public InternalApi.StatusResponse status(@RequestHeader(InternalApi.TOKEN_HEADER) String auth, @PathVariable String id) {
        authorize(auth);
        return executor.context(id).map(c -> new InternalApi.StatusResponse(c.token().isCancelled(), 0))
                .orElse(new InternalApi.StatusResponse(true, 0));
    }

    private WorkerRuntime runtime(String id, String workerId, int index, String instance, String threadName) {
        return context(id).runtimeFor(workerId, index, instance, threadName);
    }

    private ExperimentContext context(String id) {
        return executor.context(id).orElseThrow(() -> ApiException.invalidState("Experiment " + id + " is not running here"));
    }

    private void authorize(String auth) {
        if (!token.equals(auth)) {
            throw new ApiException(403, "FORBIDDEN", "Invalid internal token");
        }
    }
}

package dev.racelab.infrastructure.web;

import com.zaxxer.hikari.HikariDataSource;
import dev.racelab.application.engine.ExperimentExecutor;
import dev.racelab.application.port.DistributedLockService;
import dev.racelab.application.service.ComparisonService;
import dev.racelab.application.service.DashboardService;
import dev.racelab.application.service.EnvironmentInfo;
import dev.racelab.application.service.InspectorService;
import dev.racelab.infrastructure.remote.HttpRemoteWorkerGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Analysis", description = "Strategy comparator, dashboard, transaction inspector and system information")
@RestController
@RequestMapping("/api")
public class AnalysisController {

    private final ComparisonService comparisons;
    private final DashboardService dashboard;
    private final InspectorService inspector;
    private final EnvironmentInfo environment;
    private final ExperimentExecutor executor;
    private final DistributedLockService redis;
    private final HttpRemoteWorkerGateway peer;
    private final DataSource dataSource;

    public AnalysisController(ComparisonService comparisons, DashboardService dashboard, InspectorService inspector,
                              EnvironmentInfo environment, ExperimentExecutor executor, DistributedLockService redis,
                              HttpRemoteWorkerGateway peer, DataSource dataSource) {
        this.comparisons = comparisons;
        this.dashboard = dashboard;
        this.inspector = inspector;
        this.environment = environment;
        this.executor = executor;
        this.redis = redis;
        this.peer = peer;
        this.dataSource = dataSource;
    }

    @Operation(summary = "Run the same scenario with several strategies on isolated, identical data")
    @PostMapping("/comparisons")
    @ResponseStatus(HttpStatus.CREATED)
    public ComparisonService.Comparison compare(@RequestBody ComparisonService.CreateComparison request) {
        return comparisons.create(request);
    }

    @GetMapping("/comparisons")
    public List<ComparisonService.Comparison> listComparisons() {
        return comparisons.list();
    }

    @GetMapping("/comparisons/{id}")
    public ComparisonService.Comparison comparison(@PathVariable String id) {
        return comparisons.get(id);
    }

    @PostMapping("/comparisons/{id}/cancel")
    public ComparisonService.Comparison cancelComparison(@PathVariable String id) {
        comparisons.cancel(id);
        return comparisons.get(id);
    }

    @Operation(summary = "Indicators aggregated from persisted experiments (no demo data)")
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return dashboard.dashboard();
    }

    @Operation(summary = "Snapshot of PostgreSQL sessions, locks and blocking relations")
    @GetMapping("/inspector/snapshot")
    public InspectorService.Snapshot snapshot(@RequestParam(defaultValue = "racelab") String scope) {
        return inspector.snapshot(scope);
    }

    @GetMapping("/system/info")
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("instanceId", environment.instanceId());
        info.put("applicationVersion", environment.applicationVersion());
        info.put("javaVersion", Runtime.version().toString());
        info.put("postgresVersion", environment.postgresVersion());
        info.put("redisAvailable", redis.isAvailable());
        info.put("peers", peer.peerUrl() == null || peer.peerUrl().isBlank() ? List.of()
                : List.of(Map.of("instanceId", peer.peerInstanceId(), "url", peer.peerUrl(), "available", peer.isPeerAvailable())));
        info.put("limits", Map.of("maxThreads", 16, "maxTransactions", 200,
                "maxConcurrentExperiments", executor.maxConcurrent(), "maxDelayMs", 5000, "maxRetries", 10));
        info.put("activeExperiments", executor.activeCount());
        if (dataSource instanceof HikariDataSource h && h.getHikariPoolMXBean() != null) {
            var pool = h.getHikariPoolMXBean();
            info.put("connectionPool", Map.of("active", pool.getActiveConnections(), "idle", pool.getIdleConnections(),
                    "total", pool.getTotalConnections(), "waiting", pool.getThreadsAwaitingConnection(),
                    "max", h.getMaximumPoolSize()));
        }
        return info;
    }
}

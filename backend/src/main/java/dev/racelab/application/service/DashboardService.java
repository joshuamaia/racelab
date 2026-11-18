package dev.racelab.application.service;

import dev.racelab.domain.port.ExperimentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dashboard indicators aggregated from persisted experiments only (warm-up runs excluded). No demo data. */
@Service
public class DashboardService {

    private final JdbcTemplate jdbc;
    private final ExperimentService experiments;

    public DashboardService(JdbcTemplate jdbc, ExperimentService experiments) {
        this.jdbc = jdbc;
        this.experiments = experiments;
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT count(*) AS experiments,
                       count(*) FILTER (WHERE status = 'RUNNING') AS running,
                       count(*) FILTER (WHERE consistent = false) AS inconsistent_experiments,
                       coalesce(sum(violation_count), 0) AS inconsistencies,
                       coalesce(round(avg(duration_ms) FILTER (WHERE status = 'COMPLETED'), 1), 0) AS avg_duration_ms,
                       count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE status IN ('COMPLETED', 'FAILED')) AS finished
                FROM experiment WHERE NOT warmup
                """);
        Map<String, Object> metricSums = jdbc.queryForMap("""
                SELECT coalesce(sum(value) FILTER (WHERE m.name = 'transactionsStarted'), 0) AS transactions,
                       coalesce(sum(value) FILTER (WHERE m.name = 'deadlocks'), 0) AS deadlocks,
                       coalesce(sum(value) FILTER (WHERE m.name = 'rolledBack'), 0) AS rollbacks,
                       coalesce(sum(value) FILTER (WHERE m.name = 'optimisticConflicts'), 0) AS version_conflicts
                FROM experiment_metric m JOIN experiment e ON e.id = m.experiment_id WHERE NOT e.warmup
                """);
        long completed = ((Number) totals.get("completed")).longValue();
        long finished = ((Number) totals.get("finished")).longValue();
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("experiments", totals.get("experiments"));
        t.put("transactions", num(metricSums.get("transactions")));
        t.put("inconsistencies", totals.get("inconsistencies"));
        t.put("inconsistentExperiments", totals.get("inconsistent_experiments"));
        t.put("deadlocks", num(metricSums.get("deadlocks")));
        t.put("rollbacks", num(metricSums.get("rollbacks")));
        t.put("versionConflicts", num(metricSums.get("version_conflicts")));
        t.put("avgDurationMs", ((Number) totals.get("avg_duration_ms")).doubleValue());
        t.put("successRate", finished == 0 ? 0.0 : Math.round(completed * 1000.0 / finished) / 1000.0);
        t.put("running", totals.get("running"));

        List<Map<String, Object>> byCategory = jdbc.queryForList(
                "SELECT category, count(*) AS count FROM experiment WHERE NOT warmup GROUP BY category ORDER BY count DESC");
        List<Map<String, Object>> durationByStrategy = jdbc.queryForList("""
                SELECT strategy, round(avg(duration_ms), 1) AS "avgMs", count(*) AS runs
                FROM experiment WHERE status = 'COMPLETED' AND NOT warmup GROUP BY strategy ORDER BY strategy
                """);
        List<Map<String, Object>> failureRate = jdbc.queryForList("""
                SELECT strategy,
                       round(count(*) FILTER (WHERE status = 'FAILED' OR consistent = false)::numeric / count(*), 3) AS rate,
                       count(*) AS runs
                FROM experiment WHERE status IN ('COMPLETED', 'FAILED') AND NOT warmup GROUP BY strategy ORDER BY strategy
                """);
        List<Map<String, Object>> overTime = jdbc.queryForList("""
                SELECT date_trunc('hour', created_at) AS bucket, count(*) AS experiments,
                       count(*) FILTER (WHERE consistent = false) AS inconsistencies
                FROM experiment WHERE NOT warmup AND created_at > now() - interval '7 days'
                GROUP BY 1 ORDER BY 1
                """);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", t);
        out.put("byCategory", byCategory);
        out.put("durationByStrategy", durationByStrategy);
        out.put("failureRateByStrategy", failureRate);
        out.put("inconsistenciesOverTime", overTime);
        out.put("recent", experiments.search(new ExperimentRepository.SearchFilter(null, null, null, null, false), 0, 10).content());
        out.put("dataNote", "Computed from " + totals.get("experiments") + " persisted experiment(s) (warm-up runs excluded). "
                + "Failure rate = runs that failed technically or ended inconsistent. Success rate = completed / finished runs.");
        return out;
    }

    private static Number num(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }
}

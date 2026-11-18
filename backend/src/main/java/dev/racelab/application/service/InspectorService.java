package dev.racelab.application.service;

import dev.racelab.domain.model.LockGraph;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transaction Inspector: point-in-time snapshot of pg_stat_activity, pg_locks and pg_blocking_pids for the
 * RaceLab database. Read-only catalog queries; transient states between two snapshots are not captured.
 */
@Service
public class InspectorService {

    private final JdbcTemplate jdbc;
    private final EnvironmentInfo environment;

    public InspectorService(JdbcTemplate jdbc, EnvironmentInfo environment) {
        this.jdbc = jdbc;
        this.environment = environment;
    }

    public record Session(long pid, String applicationName, String experimentId, String workerId, String state,
                          String backendXid, Instant xactStart, Instant queryStart, Long transactionAgeMs,
                          String waitEventType, String waitEvent, String query, List<Long> blockedBy) {
    }

    public record LockRow(long pid, String lockType, String mode, boolean granted, String relation, String transactionId,
                          String virtualXid, Integer tuple, Integer page) {
    }

    public record Snapshot(Instant capturedAt, String scope, String postgresVersion, List<Session> sessions,
                           List<LockRow> locks, LockGraph graph, String note) {
    }

    public Snapshot snapshot(String scope) {
        boolean all = "database".equalsIgnoreCase(scope);
        List<Session> sessions = jdbc.query("""
                SELECT a.pid, a.application_name, a.state, a.backend_xid::text AS xid, a.xact_start, a.query_start,
                       (extract(epoch FROM clock_timestamp() - a.xact_start) * 1000)::bigint AS age_ms,
                       a.wait_event_type, a.wait_event, left(a.query, 400) AS query, pg_blocking_pids(a.pid) AS blocked_by
                FROM pg_stat_activity a
                WHERE a.datname = current_database() AND a.pid <> pg_backend_pid() AND a.backend_type = 'client backend'
                  AND (? OR a.application_name LIKE 'racelab:%' OR a.xact_start IS NOT NULL)
                ORDER BY a.xact_start NULLS LAST, a.pid
                """, (rs, i) -> {
            String app = rs.getString("application_name");
            String[] parts = app != null && app.startsWith("racelab:") ? app.split(":") : new String[0];
            Timestamp xs = rs.getTimestamp("xact_start");
            Timestamp qs = rs.getTimestamp("query_start");
            long age = rs.getLong("age_ms");
            return new Session(rs.getLong("pid"), app, parts.length > 1 ? parts[1] : null, parts.length > 2 ? parts[2] : null,
                    rs.getString("state"), rs.getString("xid"), xs == null ? null : xs.toInstant(),
                    qs == null ? null : qs.toInstant(), rs.wasNull() ? null : age, rs.getString("wait_event_type"),
                    rs.getString("wait_event"), rs.getString("query"), longs(rs.getArray("blocked_by")));
        }, all);
        List<Long> pids = sessions.stream().map(Session::pid).toList();
        List<LockRow> locks = pids.isEmpty() ? List.of() : jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT l.pid, l.locktype, l.mode, l.granted, c.relname, l.transactionid::text AS txid, l.virtualxid,
                           l.tuple, l.page
                    FROM pg_locks l LEFT JOIN pg_class c ON c.oid = l.relation
                    WHERE l.pid = ANY (?) AND l.locktype <> 'virtualxid'
                      AND (c.relname IS NULL OR c.relname NOT LIKE 'pg\\_%')
                    ORDER BY l.pid, l.granted DESC
                    """);
            ps.setArray(1, con.createArrayOf("int4", pids.stream().map(Long::intValue).toArray()));
            return ps;
        }, (rs, i) -> {
            int tuple = rs.getInt("tuple");
            Integer t = rs.wasNull() ? null : tuple;
            int page = rs.getInt("page");
            Integer p = rs.wasNull() ? null : page;
            return new LockRow(rs.getLong("pid"), rs.getString("locktype"), rs.getString("mode"), rs.getBoolean("granted"),
                    rs.getString("relname"), rs.getString("txid"), rs.getString("virtualxid"), t, p);
        });
        return new Snapshot(Instant.now(), all ? "database" : "racelab", environment.postgresVersion(), sessions, locks,
                graph(sessions), "Point-in-time snapshot of pg_stat_activity / pg_locks / pg_blocking_pids. "
                + "Transient states between two snapshots are not captured.");
    }

    private static LockGraph graph(List<Session> sessions) {
        Map<String, LockGraph.Node> nodes = new LinkedHashMap<>();
        List<LockGraph.Edge> edges = new ArrayList<>();
        Map<Long, Session> byPid = new LinkedHashMap<>();
        sessions.forEach(s -> byPid.put(s.pid(), s));
        for (Session s : sessions) {
            for (Long blocker : s.blockedBy()) {
                node(nodes, s.pid(), byPid.get(s.pid()));
                node(nodes, blocker, byPid.get(blocker));
                edges.add(new LockGraph.Edge("pid:" + blocker, "pid:" + s.pid(), "blocks (" + s.waitEvent() + ")"));
            }
        }
        boolean cycle = edges.stream().anyMatch(e -> edges.stream().anyMatch(o -> o.from().equals(e.to()) && o.to().equals(e.from())));
        return new LockGraph(LockGraph.DATABASE_OBSERVED, edges.isEmpty() ? "No session is blocked right now."
                : "Observed blocking relations (blocker → blocked).", cycle, List.copyOf(nodes.values()), edges);
    }

    private static void node(Map<String, LockGraph.Node> nodes, long pid, Session s) {
        String label = s != null && s.workerId() != null ? s.workerId() + " (pid " + pid + ")" : "pid " + pid;
        nodes.putIfAbsent("pid:" + pid, new LockGraph.Node("pid:" + pid, "TRANSACTION", label,
                s == null ? null : s.workerId(), pid, false));
    }

    private static List<Long> longs(Array arr) throws java.sql.SQLException {
        List<Long> out = new ArrayList<>();
        if (arr != null) {
            for (Object o : (Object[]) arr.getArray()) {
                out.add(((Number) o).longValue());
            }
        }
        return out;
    }
}

package dev.racelab.application.engine;

import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.model.LockGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds wait-for graphs from PostgreSQL deadlock reports or from observed lock-wait events. */
public final class LockGraphBuilder {

    /** e.g. "Process 4711 waits for ShareLock on transaction 812; blocked by process 4712." */
    private static final Pattern DEADLOCK_LINE = Pattern.compile(
            "Process (\\d+) waits for (\\w+) on (.+?); blocked by process (\\d+)\\.");

    private LockGraphBuilder() {
    }

    /** A deadlock reported by PostgreSQL (DETAIL of SQLSTATE 40P01) together with the victim session. */
    public record DeadlockReport(long victimPid, String victimWorker, String detail) {
    }

    public static LockGraph fromDeadlock(DeadlockReport report, Function<Long, String> workerOfPid) {
        Matcher m = DEADLOCK_LINE.matcher(report.detail() == null ? "" : report.detail());
        Map<String, LockGraph.Node> nodes = new LinkedHashMap<>();
        List<LockGraph.Edge> edges = new ArrayList<>();
        while (m.find()) {
            long waiter = Long.parseLong(m.group(1));
            String mode = m.group(2);
            String object = m.group(3);
            long holder = Long.parseLong(m.group(4));
            String waiterId = txNode(nodes, waiter, workerOfPid, waiter == report.victimPid());
            String holderId = txNode(nodes, holder, workerOfPid, holder == report.victimPid());
            String lockId = "lock:" + object;
            nodes.putIfAbsent(lockId, new LockGraph.Node(lockId, "LOCK", object, null, null, false));
            edges.add(new LockGraph.Edge(waiterId, lockId, "waits for " + mode));
            edges.add(new LockGraph.Edge(lockId, holderId, "held by"));
        }
        if (nodes.isEmpty()) {
            nodes.put("pid:" + report.victimPid(), new LockGraph.Node("pid:" + report.victimPid(), "TRANSACTION",
                    label(report.victimPid(), workerOfPid), report.victimWorker(), report.victimPid(), true));
        }
        return new LockGraph(LockGraph.DATABASE_REPORTED,
                "Deadlock cycle reported by PostgreSQL (SQLSTATE 40P01). The victim was aborted and rolled back.",
                !edges.isEmpty(), List.copyOf(nodes.values()), edges);
    }

    /** Reconstructs a wait-for graph from the LOCK_WAITING events observed during the experiment. */
    public static LockGraph fromObservedWaits(List<ExperimentEvent> events, Function<Long, String> workerOfPid) {
        Map<String, LockGraph.Node> nodes = new LinkedHashMap<>();
        Map<String, LockGraph.Edge> edges = new LinkedHashMap<>();
        for (ExperimentEvent e : events) {
            if (e.eventType() != EventType.LOCK_WAITING || e.source() != EventSource.DATABASE_OBSERVED
                    || e.connectionPid() == null) {
                continue;
            }
            Object blockers = e.details().get("blockedByPids");
            if (!(blockers instanceof List<?> list)) {
                continue;
            }
            String waiter = txNode(nodes, e.connectionPid(), workerOfPid, false);
            for (Object o : list) {
                long holder = ((Number) o).longValue();
                String holderId = txNode(nodes, holder, workerOfPid, false);
                String key = waiter + "->" + holderId;
                edges.putIfAbsent(key, new LockGraph.Edge(waiter, holderId,
                        "waits for (" + e.details().getOrDefault("waitEvent", "lock") + ")"));
            }
        }
        if (edges.isEmpty()) {
            return null;
        }
        return new LockGraph(LockGraph.RECONSTRUCTED,
                "Reconstructed from lock waits observed in pg_stat_activity/pg_blocking_pids during the experiment "
                        + "(sampled, transient waits shorter than the sampling interval may be missing).",
                false, List.copyOf(nodes.values()), List.copyOf(edges.values()));
    }

    private static String txNode(Map<String, LockGraph.Node> nodes, long pid, Function<Long, String> workerOfPid,
                                 boolean victim) {
        String id = "pid:" + pid;
        LockGraph.Node existing = nodes.get(id);
        if (existing == null || (victim && !existing.victim())) {
            nodes.put(id, new LockGraph.Node(id, "TRANSACTION", label(pid, workerOfPid), workerOfPid.apply(pid), pid,
                    victim));
        }
        return id;
    }

    private static String label(long pid, Function<Long, String> workerOfPid) {
        String w = workerOfPid.apply(pid);
        return (w == null ? "session" : w) + " (pid " + pid + ")";
    }
}

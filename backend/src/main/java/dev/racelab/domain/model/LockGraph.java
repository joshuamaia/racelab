package dev.racelab.domain.model;

import java.util.List;

/**
 * Wait-for graph between transactions. The {@code source} tells the user how trustworthy it is:
 * reported by PostgreSQL (deadlock error detail), observed in PostgreSQL (pg_locks / pg_blocking_pids)
 * or reconstructed from application events.
 */
public record LockGraph(String source, String description, boolean cycle, List<Node> nodes, List<Edge> edges) {

    public static final String DATABASE_REPORTED = "DATABASE_REPORTED";
    public static final String DATABASE_OBSERVED = "DATABASE_OBSERVED";
    public static final String RECONSTRUCTED = "RECONSTRUCTED";

    public record Node(String id, String kind, String label, String workerId, Long pid, boolean victim) {
    }

    public record Edge(String from, String to, String label) {
    }
}

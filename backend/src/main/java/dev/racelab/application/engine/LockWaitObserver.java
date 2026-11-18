package dev.racelab.application.engine;

import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Observes, from a separate connection, whether the sessions of the workers that declared a possible
 * database wait are really blocked: {@code pg_blocking_pids(pid)} + {@code pg_stat_activity.wait_event}.
 * Confirmed waits are published as {@code LOCK_WAITING} events with source {@code DATABASE_OBSERVED}.
 */
public final class LockWaitObserver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LockWaitObserver.class);
    private static final String QUERY = """
            SELECT a.pid, a.wait_event_type, a.wait_event, pg_blocking_pids(a.pid) AS blocked_by
            FROM pg_stat_activity a
            WHERE a.pid = ANY (?)
            """;

    private final JdbcTemplate jdbc;
    private final BlockRegistry blocks;
    private final ObservedWaitListener listener;
    private final Function<Long, String> workerOfPid;
    private volatile boolean running = true;
    private Thread thread;

    @FunctionalInterface
    public interface ObservedWaitListener {
        void onWait(String workerId, Long pid, EventType type, EventSource source, String message, Map<String, Object> details);
    }

    public LockWaitObserver(JdbcTemplate jdbc, BlockRegistry blocks, Function<Long, String> workerOfPid,
                            ObservedWaitListener listener) {
        this.jdbc = jdbc;
        this.blocks = blocks;
        this.workerOfPid = workerOfPid;
        this.listener = listener;
    }

    public synchronized void start(String name) {
        if (thread == null) {
            thread = Thread.ofPlatform().daemon().name(name).start(this::loop);
        }
    }

    private void loop() {
        while (running) {
            try {
                List<BlockRegistry.Block> pending = blocks.databaseBlocks().stream().filter(b -> !b.confirmed()).toList();
                if (!pending.isEmpty()) {
                    poll(pending);
                    Thread.sleep(15);
                } else {
                    Thread.sleep(25);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.debug("Lock observer poll failed: {}", e.getMessage());
                sleepQuietly();
            }
        }
    }

    private void poll(List<BlockRegistry.Block> pending) {
        Integer[] pids = pending.stream().map(b -> b.pid().intValue()).toArray(Integer[]::new);
        Map<Long, Row> rows = new LinkedHashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement(QUERY);
            ps.setArray(1, con.createArrayOf("int4", pids));
            return ps;
        }, rs -> {
            Array arr = rs.getArray("blocked_by");
            List<Long> blockers = new ArrayList<>();
            if (arr != null) {
                for (Object o : (Object[]) arr.getArray()) {
                    blockers.add(((Number) o).longValue());
                }
            }
            rows.put(rs.getLong("pid"), new Row(rs.getString("wait_event_type"), rs.getString("wait_event"), blockers));
        });
        for (BlockRegistry.Block b : pending) {
            Row row = rows.get(b.pid());
            if (row == null || row.blockedBy.isEmpty()) {
                continue;
            }
            blocks.confirm(b, row.blockedBy, row.waitEventType + "/" + row.waitEvent);
            List<String> blockerWorkers = row.blockedBy.stream().map(p -> {
                String w = workerOfPid.apply(p);
                return w == null ? "pid " + p : w + " (pid " + p + ")";
            }).toList();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("blockedByPids", row.blockedBy);
            details.put("blockedBy", blockerWorkers);
            details.put("waitEventType", row.waitEventType);
            details.put("waitEvent", row.waitEvent);
            details.put("reason", b.reason());
            listener.onWait(b.workerId(), b.pid(), EventType.LOCK_WAITING, EventSource.DATABASE_OBSERVED,
                    "Session " + b.pid() + " is waiting (" + row.waitEventType + "/" + row.waitEvent + ") — blocked by "
                            + String.join(", ", blockerWorkers), details);
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private record Row(String waitEventType, String waitEvent, List<Long> blockedBy) {
        @Override
        public String toString() {
            return waitEventType + "/" + waitEvent + " " + Arrays.toString(blockedBy.toArray());
        }
    }
}

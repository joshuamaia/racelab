package dev.racelab.application.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cancels experiments cooperatively: raises the token (checked at every checkpoint, rendezvous and pause)
 * and cancels the statements currently running on the experiment's database sessions with
 * {@code pg_cancel_backend}, so a worker blocked on a lock is released immediately and its transaction
 * is rolled back instead of staying open.
 */
@Component
public class ExperimentCancellationManager {

    private static final Logger log = LoggerFactory.getLogger(ExperimentCancellationManager.class);

    private final ConcurrentMap<String, Registration> registrations = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;

    public ExperimentCancellationManager(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CancellationToken register(String experimentId) {
        Registration r = registrations.computeIfAbsent(experimentId, id -> new Registration(new CancellationToken()));
        return r.token;
    }

    public void unregister(String experimentId) {
        registrations.remove(experimentId);
    }

    public Optional<CancellationToken> token(String experimentId) {
        return Optional.ofNullable(registrations.get(experimentId)).map(r -> r.token);
    }

    public void trackPid(String experimentId, long pid) {
        Registration r = registrations.get(experimentId);
        if (r != null) {
            r.pids.add(pid);
        }
    }

    public void untrackPid(String experimentId, long pid) {
        Registration r = registrations.get(experimentId);
        if (r != null) {
            r.pids.remove(pid);
        }
    }

    public boolean cancel(String experimentId, String reason) {
        Registration r = registrations.get(experimentId);
        if (r == null) {
            return false;
        }
        boolean first = r.token.cancel(reason);
        cancelBackends(experimentId, r.pids);
        return first;
    }

    private void cancelBackends(String experimentId, Collection<Long> pids) {
        for (Long pid : Set.copyOf(pids)) {
            try {
                Boolean ok = jdbc.queryForObject("SELECT pg_cancel_backend(?)", Boolean.class, pid.intValue());
                log.info("pg_cancel_backend({}) for experiment {} -> {}", pid, experimentId, ok);
            } catch (RuntimeException e) {
                log.warn("Could not cancel backend {} of experiment {}: {}", pid, experimentId, e.getMessage());
            }
        }
    }

    private record Registration(CancellationToken token, Set<Long> pids) {
        Registration(CancellationToken token) {
            this(token, ConcurrentHashMap.newKeySet());
        }
    }
}

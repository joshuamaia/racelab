package dev.racelab.support;

import dev.racelab.application.service.ExperimentService;
import dev.racelab.application.service.ExperimentView;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.model.ExperimentResult;
import dev.racelab.domain.model.ExperimentStatus;
import dev.racelab.domain.model.StrategyType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;

/**
 * Base class of the integration tests: a real PostgreSQL 17 (Testcontainers), never H2 — concurrency and
 * isolation semantics are database-specific. The container is shared by every test class (singleton).
 */
@SpringBootTest(properties = {
        "racelab.limits.max-concurrent-experiments=6",
        "racelab.limits.mutations-per-minute=10000",
        "spring.data.redis.port=1",
        "spring.data.redis.timeout=200ms",
        "spring.data.redis.connect-timeout=200ms"
})
@Import(IntegrationTest.Containers.class)
public abstract class IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    public static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return SharedPostgres.INSTANCE;
        }
    }

    static final class SharedPostgres {
        @SuppressWarnings("resource")
        static final PostgreSQLContainer INSTANCE = new PostgreSQLContainer("postgres:17-alpine")
                .withCommand("postgres", "-c", "max_connections=300", "-c", "deadlock_timeout=300ms");

        static {
            INSTANCE.start();
        }
    }

    @Autowired
    protected ExperimentService experiments;

    @Autowired
    protected JdbcTemplate jdbc;

    protected ExperimentView create(String scenarioId, StrategyType strategy, ExperimentConfiguration cfg) {
        return experiments.create(new ExperimentService.CreateCommand(null, scenarioId, strategy, null, cfg, false,
                "test", null, false, null));
    }

    protected ExperimentResult run(String scenarioId, StrategyType strategy, ExperimentConfiguration cfg) {
        ExperimentView v = create(scenarioId, strategy, cfg);
        experiments.start(v.id());
        awaitTerminal(v.id(), Duration.ofSeconds(60));
        return experiments.result(v.id());
    }

    protected ExperimentResult runPreset(String presetId) {
        ExperimentView v = experiments.create(new ExperimentService.CreateCommand(presetId, null, null, null, null, true,
                "test", null, false, null));
        awaitTerminal(v.id(), Duration.ofSeconds(60));
        return experiments.result(v.id());
    }

    protected ExperimentStatus awaitTerminal(String id, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            ExperimentStatus s = experiments.view(id).status();
            if (s.isTerminal()) {
                return s;
            }
            sleep(50);
        }
        throw new AssertionError("Experiment " + id + " did not finish within " + timeout);
    }

    protected List<ExperimentEvent> events(String id) {
        return experiments.events(id);
    }

    protected static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

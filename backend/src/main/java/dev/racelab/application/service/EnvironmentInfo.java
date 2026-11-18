package dev.racelab.application.service;

import dev.racelab.application.port.DistributedLockService;
import dev.racelab.application.port.MessageBroker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Environment facts included in every result and report (results depend on them). */
@Component
public class EnvironmentInfo {

    private final JdbcTemplate jdbc;
    private final DistributedLockService locks;
    private final MessageBroker broker;
    private final String instanceId;
    private final String appVersion;
    private volatile String postgresVersion;

    public EnvironmentInfo(JdbcTemplate jdbc, DistributedLockService locks, MessageBroker broker,
                           @Value("${racelab.instance-id}") String instanceId,
                           @Value("${racelab.version:1.0.0}") String appVersion) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.broker = broker;
        this.instanceId = instanceId;
        this.appVersion = appVersion;
    }

    public String instanceId() {
        return instanceId;
    }

    public String applicationVersion() {
        return appVersion;
    }

    public String postgresVersion() {
        if (postgresVersion == null) {
            try {
                postgresVersion = jdbc.queryForObject("SELECT version()", String.class);
            } catch (RuntimeException e) {
                return "unavailable";
            }
        }
        return postgresVersion;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("instanceId", instanceId);
        env.put("applicationVersion", appVersion);
        env.put("postgresVersion", postgresVersion());
        env.put("javaVersion", Runtime.version().toString());
        env.put("springBootVersion", SpringBootVersion.getVersion());
        env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        env.put("redisAvailable", locks.isAvailable());
        env.put("messageBroker", broker.name());
        try {
            env.put("deadlockTimeout", jdbc.queryForObject("SHOW deadlock_timeout", String.class));
            env.put("defaultTransactionIsolation", jdbc.queryForObject("SHOW default_transaction_isolation", String.class));
            env.put("maxConnections", jdbc.queryForObject("SHOW max_connections", String.class));
        } catch (RuntimeException ignored) {
            // informational only
        }
        return env;
    }
}

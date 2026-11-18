package dev.racelab.scenario;

import dev.racelab.domain.model.ErrorType;
import dev.racelab.domain.model.ExperimentResult;
import dev.racelab.domain.model.TxStatus;
import dev.racelab.scenario.support.ConfigBuilder;
import dev.racelab.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import static dev.racelab.domain.model.StrategyType.FENCING_TOKEN;
import static dev.racelab.domain.model.StrategyType.REDIS_LOCK;
import static org.assertj.core.api.Assertions.assertThat;

/** Redis distributed lock, lease expiration and fencing tokens against a real Redis. */
class RedisLocksIT extends IntegrationTest {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "2s");
        registry.add("spring.data.redis.connect-timeout", () -> "2s");
    }

    @Test
    void redisLockSerializesWithdrawalsInsideTheLease() {
        ExperimentResult r = run("bank-withdrawal", REDIS_LOCK, ConfigBuilder.defaults().resourceCode("ACC-A").lockTtlMs(5000).build());
        assertThat(r.consistent()).isTrue();
        assertThat(r.transactions()).filteredOn(t -> t.status() == TxStatus.COMMITTED).hasSize(1);
    }

    @Test
    void expiredLeaseLetsTwoOwnersWriteAndFencingTokensStopTheStaleOne() {
        ExperimentResult expired = run("lock-expiration", REDIS_LOCK, ConfigBuilder.defaults().lockTtlMs(500)
                .initialValue("1000.00").operationValue("300.00").transactionTimeoutMs(15000).build());
        assertThat(expired.consistent()).isFalse();
        assertThat(expired.transactions()).allMatch(t -> t.status() == TxStatus.COMMITTED);

        ExperimentResult fenced = run("lock-expiration", FENCING_TOKEN, ConfigBuilder.defaults().lockTtlMs(500)
                .initialValue("1000.00").operationValue("300.00").transactionTimeoutMs(15000).build());
        assertThat(fenced.consistent()).isTrue();
        assertThat(fenced.transactions()).anyMatch(t -> t.errorType() == ErrorType.LOCK_NOT_ACQUIRED);
    }
}

package dev.racelab.scenario;

import dev.racelab.domain.model.ErrorType;
import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentResult;
import dev.racelab.domain.model.IsolationLevel;
import dev.racelab.domain.model.StateRow;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.TxStatus;
import dev.racelab.scenario.support.ConfigBuilder;
import dev.racelab.support.IntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static dev.racelab.domain.model.StrategyType.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Lost update and the strategies that prevent it, on a real PostgreSQL. */
class BankWithdrawalIT extends IntegrationTest {

    private static final String SCENARIO = "bank-withdrawal";

    private static ConfigBuilder twoWithdrawals() {
        return ConfigBuilder.defaults().resourceCode("ACC-A").initialValue("1000.00").operationValue("800.00");
    }

    @RepeatedTest(3)
    void lostUpdateIsReproducedDeterministicallyWithoutControl() {
        ExperimentResult r = run(SCENARIO, NONE, twoWithdrawals().build());

        assertThat(r.consistent()).isFalse();
        assertThat(r.transactions()).hasSize(2).allMatch(TransactionOutcome::committed);
        StateRow balance = row(r, "balance");
        assertThat(balance.actual()).isEqualTo("200.00");
        assertThat(balance.expected()).isEqualTo("-600.00");
        assertThat(r.violations()).anyMatch(v -> v.rule().startsWith("initial balance"));
        assertThat(r.metrics().committed()).isEqualTo(2);
        assertThat(events(r.experimentId())).anyMatch(e -> e.eventType() == EventType.INCONSISTENCY_DETECTED);
    }

    @Test
    void repeatableReadTurnsTheLostUpdateIntoASerializationFailure() {
        ExperimentResult r = run(SCENARIO, NONE, twoWithdrawals().isolation(IsolationLevel.REPEATABLE_READ).build());

        assertThat(r.consistent()).isTrue();
        assertThat(r.transactions()).filteredOn(t -> t.status() == TxStatus.ROLLED_BACK)
                .singleElement().satisfies(t -> {
                    assertThat(t.sqlState()).isEqualTo("40001");
                    assertThat(t.errorType()).isEqualTo(ErrorType.SERIALIZATION_FAILURE);
                });
    }

    @Test
    void optimisticLockingProducesARealVersionConflict() {
        ExperimentResult r = run(SCENARIO, OPTIMISTIC_LOCKING, twoWithdrawals().build());

        assertThat(r.consistent()).isTrue();
        assertThat(count(r.transactions(), TxStatus.COMMITTED)).isEqualTo(1);
        assertThat(r.transactions()).anyMatch(t -> t.errorType() == ErrorType.OPTIMISTIC_LOCK);
        assertThat(r.metrics().optimisticConflicts()).isEqualTo(1);
        assertThat(row(r, "balance").actual()).isEqualTo("200.00");
    }

    @Test
    void optimisticRetryRereadsAndIsRejectedByTheBusinessRule() {
        ExperimentResult r = run(SCENARIO, OPTIMISTIC_LOCKING, twoWithdrawals().retries(2).build());

        assertThat(r.consistent()).isTrue();
        assertThat(r.metrics().retries()).isEqualTo(1);
        assertThat(r.transactions()).anyMatch(t -> t.status() == TxStatus.REJECTED && t.attempts() == 2);
    }

    @Test
    void pessimisticLockingProducesARealWaitObservedInPostgres() {
        ExperimentResult r = run(SCENARIO, PESSIMISTIC_LOCKING, twoWithdrawals().delayBeforeCommitMs(300).build());

        assertThat(r.consistent()).isTrue();
        assertThat(count(r.transactions(), TxStatus.COMMITTED)).isEqualTo(1);
        assertThat(count(r.transactions(), TxStatus.REJECTED)).isEqualTo(1);
        assertThat(r.metrics().lockWaitTimeMs()).isGreaterThanOrEqualTo(200);
        assertThat(events(r.experimentId())).anyMatch(e -> e.eventType() == EventType.LOCK_WAITING
                && e.source() == EventSource.DATABASE_OBSERVED);
        assertThat(r.lockGraph()).isNotNull();
    }

    @Test
    void atomicUpdatePreservesTheInvariant() {
        ExperimentResult r = run(SCENARIO, ATOMIC_UPDATE, twoWithdrawals().build());

        assertThat(r.consistent()).isTrue();
        assertThat(count(r.transactions(), TxStatus.COMMITTED)).isEqualTo(1);
        assertThat(count(r.transactions(), TxStatus.REJECTED)).isEqualTo(1);
        assertThat(row(r, "balance").actual()).isEqualTo("200.00");
    }

    @Test
    void serializableAbortsTheSecondWriterWith40001() {
        ExperimentResult r = run(SCENARIO, SERIALIZABLE, twoWithdrawals().build());

        assertThat(r.consistent()).isTrue();
        assertThat(r.transactions()).anyMatch(t -> "40001".equals(t.sqlState()));
    }

    @Test
    void jvmLocksAndAdvisoryLocksProtectASingleInstance() {
        for (var strategy : List.of(JAVA_SYNCHRONIZED, REENTRANT_LOCK, ADVISORY_LOCK)) {
            ExperimentResult r = run(SCENARIO, strategy, twoWithdrawals().build());
            assertThat(r.consistent()).as(strategy.name()).isTrue();
            assertThat(count(r.transactions(), TxStatus.COMMITTED)).as(strategy.name()).isEqualTo(1);
        }
    }

    @Test
    void contentionWithManyThreadsStaysConsistentUnderAtomicUpdate() {
        ExperimentResult r = run(SCENARIO, ATOMIC_UPDATE, ConfigBuilder.defaults().resourceCode("ACC-C")
                .initialValue("1000.00").operationValue("30.00").threads(8).transactions(40).build());

        assertThat(r.consistent()).isTrue();
        // 1000 / 30 = 33 withdrawals fit; the remaining 7 are rejected by the guard
        assertThat(count(r.transactions(), TxStatus.COMMITTED)).isEqualTo(33);
        assertThat(new BigDecimal(row(r, "balance").actual())).isEqualByComparingTo("10.00");
    }

    private static StateRow row(ExperimentResult r, String attribute) {
        return r.stateRows().stream().filter(s -> s.attribute().equals(attribute)).findFirst().orElseThrow();
    }

    private static long count(List<TransactionOutcome> outcomes, TxStatus status) {
        return outcomes.stream().filter(o -> o.status() == status).count();
    }
}

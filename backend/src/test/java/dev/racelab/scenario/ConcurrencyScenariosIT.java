package dev.racelab.scenario;

import dev.racelab.domain.model.ErrorType;
import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentResult;
import dev.racelab.domain.model.FailureMode;
import dev.racelab.domain.model.IsolationLevel;
import dev.racelab.domain.model.LockGraph;
import dev.racelab.domain.model.StateRow;
import dev.racelab.domain.model.TxStatus;
import dev.racelab.scenario.support.ConfigBuilder;
import dev.racelab.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static dev.racelab.domain.model.StrategyType.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Inventory, seats, payments (idempotency), deadlock, write skew, order processing and outbox on PostgreSQL. */
class ConcurrencyScenariosIT extends IntegrationTest {

    @Test
    void inventoryOversellsWithoutControlAndIsProtectedByAtomicUpdate() {
        var cfg = ConfigBuilder.defaults().initialValue("10").operationValue("7").resourceCode("NOTEBOOK").build();
        ExperimentResult unsafe = run("inventory", NONE, cfg);
        assertThat(unsafe.consistent()).isFalse();
        assertThat(row(unsafe, "units sold").actual()).isEqualTo("14");

        ExperimentResult negative = run("inventory", ATOMIC_UNGUARDED, cfg);
        assertThat(row(negative, "stock").actual()).isEqualTo("-4");

        ExperimentResult safe = run("inventory", ATOMIC_UPDATE, cfg);
        assertThat(safe.consistent()).isTrue();
        assertThat(row(safe, "stock").actual()).isEqualTo("3");
        assertThat(safe.transactions()).filteredOn(t -> t.status() == TxStatus.REJECTED).hasSize(1);
    }

    @Test
    void seatDoubleBookingAndUniqueConstraint() {
        var cfg = ConfigBuilder.defaults().resourceCode("A01").build();
        assertThat(run("seat-reservation", NONE, cfg).consistent()).isFalse();

        ExperimentResult unique = run("seat-reservation", UNIQUE_CONSTRAINT, cfg);
        assertThat(unique.consistent()).isTrue();
        assertThat(unique.transactions()).anyMatch(t -> "23505".equals(t.sqlState()));

        assertThat(run("seat-reservation", ATOMIC_UPDATE, cfg).consistent()).isTrue();
        assertThat(run("seat-reservation", PESSIMISTIC_LOCKING, cfg).consistent()).isTrue();
    }

    @Test
    void idempotencyKeyPreventsDuplicatePaymentAndReplaysTheResponse() {
        var cfg = ConfigBuilder.defaults().operationValue("150.00").delayBeforeCommitMs(150).build();
        assertThat(run("duplicate-payment", NONE, cfg).consistent()).isFalse();

        ExperimentResult idem = run("duplicate-payment", IDEMPOTENCY_KEY, cfg);
        assertThat(idem.consistent()).isTrue();
        assertThat(idem.transactions()).allMatch(t -> t.status() == TxStatus.COMMITTED);
        assertThat(idem.transactions()).anyMatch(t -> Boolean.TRUE.equals(t.detail("replayed")));
        assertThat(events(idem.experimentId())).anyMatch(e -> e.eventType() == EventType.LOCK_WAITING
                && e.source() == EventSource.DATABASE_OBSERVED);
    }

    @Test
    void realDeadlockIsDetectedByPostgresAndReportedAsACycle() {
        ExperimentResult r = run("deadlock", NONE, ConfigBuilder.defaults().operationValue("100.00").retries(0).build());

        assertThat(r.consistent()).isTrue();
        assertThat(r.metrics().deadlocks()).isEqualTo(1);
        assertThat(r.transactions()).anyMatch(t -> "40P01".equals(t.sqlState()) && t.errorType() == ErrorType.DEADLOCK);
        assertThat(r.lockGraph()).isNotNull();
        assertThat(r.lockGraph().source()).isEqualTo(LockGraph.DATABASE_REPORTED);
        assertThat(r.lockGraph().cycle()).isTrue();
        assertThat(r.recommendations()).anyMatch(rec -> rec.strategies().contains(LOCK_ORDERING));
    }

    @Test
    void deadlockVictimSucceedsOnBoundedRetryAndLockOrderingAvoidsIt() {
        ExperimentResult retried = run("deadlock", NONE, ConfigBuilder.defaults().operationValue("100.00").retries(1).build());
        assertThat(retried.transactions()).allMatch(t -> t.status() == TxStatus.COMMITTED);
        assertThat(retried.metrics().retries()).isEqualTo(1);

        ExperimentResult ordered = run("deadlock", LOCK_ORDERING, ConfigBuilder.defaults().operationValue("100.00").build());
        assertThat(ordered.metrics().deadlocks()).isZero();
        assertThat(ordered.transactions()).allMatch(t -> t.status() == TxStatus.COMMITTED);
    }

    @Test
    void writeSkewHappensUnderRepeatableReadAndIsAbortedUnderSerializable() {
        ExperimentResult rr = run("write-skew", NONE, ConfigBuilder.defaults().isolation(IsolationLevel.REPEATABLE_READ).build());
        assertThat(rr.consistent()).isFalse();
        assertThat(rr.observations()).anyMatch(o -> o.phenomenon().equals("WRITE_SKEW") && o.reproduced());

        ExperimentResult ser = run("write-skew", SERIALIZABLE, ConfigBuilder.defaults().build());
        assertThat(ser.consistent()).isTrue();
        assertThat(ser.transactions()).anyMatch(t -> "40001".equals(t.sqlState()));

        assertThat(run("write-skew", PESSIMISTIC_LOCKING, ConfigBuilder.defaults().build()).consistent()).isTrue();
        assertThat(run("write-skew", ADVISORY_LOCK, ConfigBuilder.defaults().build()).consistent()).isTrue();
    }

    @Test
    void orderProcessingDuplicatesWithoutClaimAndNotWithSkipLocked() {
        var cfg = ConfigBuilder.defaults().resourceCount(3).build();
        assertThat(run("order-processing", NONE, cfg).consistent()).isFalse();
        assertThat(run("order-processing", SKIP_LOCKED, cfg).consistent()).isTrue();
        assertThat(run("order-processing", ATOMIC_CLAIM, cfg).consistent()).isTrue();
        assertThat(run("order-processing", IDEMPOTENCY_KEY, cfg).consistent()).isTrue();
    }

    @Test
    void outboxNeverLosesEventsAndTheIdempotentConsumerAbsorbsRedelivery() {
        var dualWrite = run("outbox", NONE, ConfigBuilder.defaults().transactions(3).failureMode(FailureMode.CRASH_AFTER_COMMIT).build());
        assertThat(dualWrite.consistent()).isFalse();
        assertThat(dualWrite.violations()).anyMatch(v -> v.rule().equals("every committed order is announced"));

        var outbox = run("outbox", OUTBOX, ConfigBuilder.defaults().transactions(3).threads(2)
                .failureMode(FailureMode.RELAY_CRASH_BEFORE_ACK).build());
        assertThat(outbox.consistent()).isTrue();
        assertThat(row(outbox, "duplicate deliveries").actual()).isEqualTo("1");
        assertThat(row(outbox, "invoices").actual()).isEqualTo("3");

        var naive = run("outbox", OUTBOX_NAIVE_CONSUMER, ConfigBuilder.defaults().transactions(3)
                .failureMode(FailureMode.RELAY_CRASH_BEFORE_ACK).build());
        assertThat(naive.consistent()).isFalse();
    }

    private static StateRow row(ExperimentResult r, String attribute) {
        return r.stateRows().stream().filter(s -> s.attribute().equals(attribute)).findFirst().orElseThrow();
    }
}

package dev.racelab.engine;

import dev.racelab.application.engine.BlockRegistry;
import dev.racelab.application.engine.ConsistencyValidator;
import dev.racelab.application.engine.Invariant;
import dev.racelab.application.engine.LockGraphBuilder;
import dev.racelab.application.engine.Rendezvous;
import dev.racelab.application.engine.ScenarioExceptions;
import dev.racelab.application.engine.SqlErrorClassifier;
import dev.racelab.application.recommendation.RecommendationEngine;
import dev.racelab.domain.model.ErrorType;
import dev.racelab.domain.model.LockGraph;
import dev.racelab.domain.model.Metrics;
import dev.racelab.domain.model.Severity;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.model.Violation;
import dev.racelab.scenario.support.ConfigBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EngineUnitTest {

    @Test
    void consistencyValidatorReportsOnlyBrokenInvariants() {
        List<Violation> v = new ConsistencyValidator().validate(List.of(
                Invariant.rule("balance").equalTo(new BigDecimal("-600.00"), new BigDecimal("200.00")).severity(Severity.CRITICAL).build(),
                Invariant.rule("seats").atMost(1, 1).build(),
                Invariant.rule("doctors").atLeast(1, 0).build()));
        assertThat(v).extracting(Violation::rule).containsExactly("balance", "doctors");
        assertThat(v.getFirst().difference()).isEqualTo("800.00");
        assertThat(v.get(1).expected()).isEqualTo("≥ 1");
    }

    @Test
    void classifierUsesSqlStatesAndDomainExceptions() {
        SqlErrorClassifier c = new SqlErrorClassifier();
        assertThat(c.classify(wrap("40P01"), false).errorType()).isEqualTo(ErrorType.DEADLOCK);
        assertThat(c.classify(wrap("40001"), false).errorType()).isEqualTo(ErrorType.SERIALIZATION_FAILURE);
        assertThat(c.classify(wrap("23505"), false).errorType()).isEqualTo(ErrorType.UNIQUE_VIOLATION);
        assertThat(c.classify(wrap("55P03"), false).errorType()).isEqualTo(ErrorType.LOCK_TIMEOUT);
        assertThat(c.classify(wrap("57014"), true).errorType()).isEqualTo(ErrorType.CANCELLED);
        assertThat(c.classify(wrap("57014"), false).errorType()).isEqualTo(ErrorType.TRANSACTION_TIMEOUT);
        assertThat(c.classify(new ObjectOptimisticLockingFailureException("Account", 1L), false).errorType())
                .isEqualTo(ErrorType.OPTIMISTIC_LOCK);
        assertThat(c.classify(new ScenarioExceptions.BusinessRuleRejection("X", "no money"), false).errorType())
                .isEqualTo(ErrorType.BUSINESS_RULE);
        assertThat(ErrorType.DEADLOCK.isRetryable()).isTrue();
        assertThat(ErrorType.BUSINESS_RULE.isRetryable()).isFalse();
    }

    @Test
    void rendezvousReleasesWhenTheOtherWorkerIsBlocked() throws Exception {
        BlockRegistry blocks = new BlockRegistry(() -> { });
        Predicate<String> blocked = blocks::isBlocked;
        Rendezvous r = new Rendezvous(List.of("a", "b"), blocked);
        CompletableFuture<Boolean> a = CompletableFuture.supplyAsync(() -> r.await("p", "a", 5_000, () -> false));
        Thread.sleep(100);
        assertThat(a).isNotDone();
        blocks.block("b", BlockRegistry.Kind.JVM, "monitor", null); // JVM waits are known for sure
        r.wake();
        assertThat(a.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rendezvousTimesOutInsteadOfHanging() {
        Rendezvous r = new Rendezvous(List.of("a", "b"), w -> false);
        assertThat(r.await("p", "a", 150, () -> false)).isFalse();
    }

    @Test
    void deadlockDetailIsParsedIntoACycle() {
        String detail = "Process 101 waits for ShareLock on transaction 900; blocked by process 202.\n"
                + "Process 202 waits for ShareLock on transaction 901; blocked by process 101.";
        LockGraph g = LockGraphBuilder.fromDeadlock(new LockGraphBuilder.DeadlockReport(202, "worker-2", detail),
                pid -> pid == 101 ? "worker-1" : "worker-2");
        assertThat(g.cycle()).isTrue();
        assertThat(g.nodes()).anyMatch(n -> n.victim() && n.pid() == 202);
        assertThat(g.edges()).hasSize(4);
    }

    @Test
    void recommendationsAreDrivenByWhatHappened() {
        RecommendationEngine engine = new RecommendationEngine();
        Metrics deadlock = metrics(1, 0);
        var recs = engine.recommend(new RecommendationEngine.Input("deadlock", StrategyType.NONE,
                ConfigBuilder.defaults().build(), List.of(), deadlock, List.of(), List.of()));
        assertThat(recs).anyMatch(r -> r.id().equals("deadlock") && !r.tradeOffs().isEmpty());

        Violation lost = new Violation(null, "initial balance", "", "-600", "200", "800", List.of(), Severity.CRITICAL, "", List.of());
        var lostRecs = engine.recommend(new RecommendationEngine.Input("bank-withdrawal", StrategyType.NONE,
                ConfigBuilder.defaults().build(), List.of(lost), metrics(0, 0), List.of(), List.of()));
        assertThat(lostRecs.getFirst().id()).isEqualTo("lost-update");
        assertThat(lostRecs.getFirst().strategies()).contains(StrategyType.ATOMIC_UPDATE);
    }

    @Test
    void classifierIsMockFriendlyForWrappedDriverExceptions() {
        SQLException sql = mock(SQLException.class);
        when(sql.getSQLState()).thenReturn("23505");
        when(sql.getMessage()).thenReturn("duplicate key");
        var c = new SqlErrorClassifier().classify(new DataIntegrityViolationException("dup", sql), false);
        assertThat(c.sqlState()).isEqualTo("23505");
    }

    private static Metrics metrics(int deadlocks, int optimistic) {
        return new Metrics(100, 10, 10, 10, 10, 1, 2, 2, 1, 1, 0, deadlocks + optimistic, deadlocks, 0, optimistic,
                0, 0, 0, 0, 0);
    }

    private static RuntimeException wrap(String state) {
        return new RuntimeException("wrapped", new SQLException("db error", state));
    }
}

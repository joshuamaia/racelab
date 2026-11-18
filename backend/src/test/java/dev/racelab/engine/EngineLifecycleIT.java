package dev.racelab.engine;

import dev.racelab.application.service.ComparisonService;
import dev.racelab.application.service.ExperimentView;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExecutionMode;
import dev.racelab.domain.model.ExperimentEvent;
import dev.racelab.domain.model.ExperimentResult;
import dev.racelab.domain.model.ExperimentStatus;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.scenario.support.ConfigBuilder;
import dev.racelab.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static dev.racelab.domain.model.StrategyType.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Step mode, cancellation, isolation between concurrent experiments, event ordering, rerun and comparisons. */
class EngineLifecycleIT extends IntegrationTest {

    @Autowired
    ComparisonService comparisons;

    @Test
    void stepByStepReleasesCheckpointsOneByOne() {
        ExperimentView v = create("bank-withdrawal", NONE, ConfigBuilder.defaults().resourceCode("ACC-A")
                .mode(ExecutionMode.STEP_BY_STEP).build());
        experiments.start(v.id());
        int steps = 0;
        while (!experiments.view(v.id()).status().isTerminal() && steps < 60) {
            var state = experiments.view(v.id()).stepState();
            if (state != null && !state.pending().isEmpty()) {
                experiments.step(v.id(), null);
                steps++;
            } else {
                sleep(30);
            }
        }
        assertThat(awaitTerminal(v.id(), Duration.ofSeconds(30))).isEqualTo(ExperimentStatus.COMPLETED);
        assertThat(steps).isGreaterThanOrEqualTo(8); // begin/read/validate/write/commit for each transaction
        assertThat(events(v.id())).anyMatch(e -> e.eventType() == EventType.CHECKPOINT_RELEASED);
        // the user-controlled interleaving (oldest checkpoint first) also produced the lost update
        assertThat(experiments.result(v.id()).consistent()).isFalse();
    }

    @Test
    void cancellationReleasesBlockedTransactionsAndRollsBack() {
        ExperimentView v = create("bank-withdrawal", PESSIMISTIC_LOCKING, ConfigBuilder.defaults().resourceCode("ACC-A")
                .delayBeforeCommitMs(5000).build());
        experiments.start(v.id());
        sleep(800);
        experiments.cancel(v.id());
        assertThat(awaitTerminal(v.id(), Duration.ofSeconds(10))).isEqualTo(ExperimentStatus.CANCELLED);
        Long open = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name LIKE ?",
                Long.class, "racelab:" + v.id() + "%");
        assertThat(open).isZero();
        assertThat(events(v.id()).getLast().eventType()).isEqualTo(EventType.EXPERIMENT_CANCELLED);
    }

    @Test
    void concurrentExperimentsDoNotInterfere() {
        var cfg = ConfigBuilder.defaults().resourceCode("ACC-A").build();
        List<CompletableFuture<ExperimentResult>> runs = List.of(
                CompletableFuture.supplyAsync(() -> run("bank-withdrawal", NONE, cfg)),
                CompletableFuture.supplyAsync(() -> run("bank-withdrawal", ATOMIC_UPDATE, cfg)),
                CompletableFuture.supplyAsync(() -> run("bank-withdrawal", OPTIMISTIC_LOCKING, cfg)));
        List<ExperimentResult> results = runs.stream().map(CompletableFuture::join).toList();

        assertThat(results.get(0).consistent()).isFalse();
        assertThat(results.get(1).consistent()).isTrue();
        assertThat(results.get(2).consistent()).isTrue();
        for (ExperimentResult r : results) {
            assertThat(r.stateRows().getFirst().initial()).isEqualTo("1000.00");
            Long accounts = jdbc.queryForObject("SELECT count(*) FROM account WHERE experiment_id = ?", Long.class, r.experimentId());
            assertThat(accounts).isEqualTo(1);
        }
    }

    @Test
    void eventsAreSequentialAndSurviveRollbacks() {
        ExperimentResult r = run("bank-withdrawal", OPTIMISTIC_LOCKING, ConfigBuilder.defaults().resourceCode("ACC-A").build());
        List<ExperimentEvent> persisted = experiments.events(r.experimentId());
        for (int i = 0; i < persisted.size(); i++) {
            assertThat(persisted.get(i).sequence()).isEqualTo(i + 1);
        }
        assertThat(persisted).anyMatch(e -> e.eventType() == EventType.ROLLBACK_COMPLETED);
        assertThat(persisted.getFirst().eventType()).isEqualTo(EventType.EXPERIMENT_CREATED);
        assertThat(persisted.getLast().eventType()).isEqualTo(EventType.EXPERIMENT_COMPLETED);
    }

    @Test
    void rerunUsesANewIdAndFreshData() {
        ExperimentResult first = run("bank-withdrawal", NONE, ConfigBuilder.defaults().resourceCode("ACC-A").build());
        ExperimentView again = experiments.rerun(first.experimentId(), true, "test");
        awaitTerminal(again.id(), Duration.ofSeconds(30));
        assertThat(again.id()).isNotEqualTo(first.experimentId());
        assertThat(again.rerunOf()).isEqualTo(first.experimentId());
        assertThat(experiments.result(again.id()).stateRows().getFirst().initial()).isEqualTo("1000.00");
    }

    @Test
    void comparisonRunsEveryStrategyOnIdenticalIsolatedData() {
        var c = comparisons.create(new ComparisonService.CreateComparison(null, "bank-withdrawal",
                List.of(NONE, ATOMIC_UPDATE, OPTIMISTIC_LOCKING), null, 2, 1));
        long deadline = System.currentTimeMillis() + 90_000;
        while ("RUNNING".equals(comparisons.get(c.id()).status()) && System.currentTimeMillis() < deadline) {
            sleep(100);
        }
        var done = comparisons.get(c.id());
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.results()).hasSize(3).allMatch(s -> s.measuredRuns() == 2);
        assertThat(done.results().get(0).consistencyPreserved()).isFalse();
        assertThat(done.results().get(1).consistencyPreserved()).isTrue();
        assertThat(done.progress().completedRuns()).isEqualTo(9);
        assertThat(done.disclaimer()).contains("hardware");
    }

    @Test
    void deleteRemovesHistoryAndNamespacedData() {
        ExperimentResult r = run("inventory", StrategyType.NONE, ConfigBuilder.defaults().initialValue("10")
                .operationValue("7").resourceCode("NOTEBOOK").build());
        experiments.delete(r.experimentId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product WHERE experiment_id = ?", Long.class, r.experimentId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM experiment WHERE id = ?", Long.class, r.experimentId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product WHERE experiment_id = 'TEMPLATE'", Long.class)).isEqualTo(3);
    }
}

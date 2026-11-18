package dev.racelab.scenario.isolation;

import dev.racelab.application.engine.Experiment;
import dev.racelab.application.engine.ExperimentContext;
import dev.racelab.application.engine.ScenarioReport;
import dev.racelab.domain.model.Category;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.IsolationLevel;
import dev.racelab.domain.model.Observation;
import dev.racelab.domain.model.ResourceState;
import dev.racelab.domain.model.ScenarioDescriptor;
import dev.racelab.domain.model.StateRow;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.scenario.isolation.IsolationProbes.ProbeResult;
import dev.racelab.scenario.support.ConfigBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** The isolation experiments (Isolation Lab): one bean per phenomenon plus a combined probe. */
@Configuration(proxyBeanMethods = false)
public class IsolationExperiments {

    static final List<String> PARAMS = List.of("isolationLevel", "initialValue", "operationValue");
    static final Map<String, String> LABELS = Map.of("isolationLevel", "Isolation level of the reader",
            "initialValue", "Initial balance", "operationValue", "Amount changed by the writer");

    @Bean
    Experiment dirtyReadExperiment(IsolationProbes probes) {
        return new Single("dirty-read", "Dirty read probe",
                "A reader runs while a writer holds an uncommitted change, then the writer rolls back.",
                probes::dirtyRead, IsolationLevel.READ_UNCOMMITTED);
    }

    @Bean
    Experiment nonRepeatableReadExperiment(IsolationProbes probes) {
        return new Single("non-repeatable-read", "Non-repeatable read probe",
                "A reader reads a row twice; between the reads a writer commits a change.",
                probes::nonRepeatableRead, IsolationLevel.READ_COMMITTED);
    }

    @Bean
    Experiment phantomReadExperiment(IsolationProbes probes) {
        return new Single("phantom-read", "Phantom read probe",
                "A reader repeats a range query; between the queries a writer commits a matching row.",
                probes::phantomRead, IsolationLevel.READ_COMMITTED);
    }

    @Bean
    Experiment isolationProbeExperiment(IsolationProbes probes) {
        return new Experiment() {
            @Override
            public ScenarioDescriptor descriptor() {
                return IsolationExperiments.descriptor("isolation-probe", "Isolation level probe (all read phenomena)",
                        "Runs the dirty, non-repeatable and phantom read probes at the selected isolation level.",
                        IsolationLevel.READ_COMMITTED);
            }

            @Override
            public ScenarioReport execute(ExperimentConfiguration cfg, ExperimentContext ctx) {
                List<ProbeResult> results = List.of(probes.dirtyRead(ctx, cfg.isolationLevel()),
                        probes.nonRepeatableRead(ctx, cfg.isolationLevel()), probes.phantomRead(ctx, cfg.isolationLevel()));
                return report(cfg.isolationLevel(), results);
            }
        };
    }

    static ScenarioDescriptor descriptor(String id, String name, String description, IsolationLevel level) {
        return new ScenarioDescriptor(id, name, Category.ISOLATION, description,
                "Leituras concorrentes de saldo e de movimentações bancárias.",
                "Observation only: reports which read phenomena PostgreSQL allows at the chosen level",
                List.of(StrategyType.ISOLATION_LEVEL), StrategyType.ISOLATION_LEVEL, false, false, PARAMS, LABELS, List.of(),
                ConfigBuilder.defaults().isolation(level).threads(2).transactions(2).initialValue("1000.00")
                        .operationValue("500.00").build());
    }

    static ScenarioReport report(IsolationLevel level, List<ProbeResult> results) {
        List<Observation> observations = results.stream().map(ProbeResult::observation).toList();
        List<StateRow> rows = new ArrayList<>();
        List<ResourceState> initial = new ArrayList<>();
        List<ResourceState> fin = new ArrayList<>();
        for (ProbeResult r : results) {
            rows.addAll(r.rows());
            initial.add(r.initial());
            fin.add(r.fin());
        }
        StringBuilder summary = new StringBuilder("At " + level.sqlName());
        if (level != level.effectiveInPostgres()) {
            summary.append(" (runs as ").append(level.effectiveInPostgres().sqlName()).append(" in PostgreSQL)");
        }
        summary.append(": ");
        summary.append(String.join("; ", observations.stream()
                .map(o -> o.phenomenon().replace('_', ' ').toLowerCase() + " " + (o.reproduced() ? "REPRODUCED" : "prevented"))
                .toList()));
        return new ScenarioReport(summary.append('.').toString(), initial, fin, rows, List.of(), observations);
    }

    /** One probe exposed as its own scenario. */
    static final class Single implements Experiment {
        private final ScenarioDescriptor descriptor;
        private final BiFunction<ExperimentContext, IsolationLevel, ProbeResult> probe;

        Single(String id, String name, String description,
               BiFunction<ExperimentContext, IsolationLevel, ProbeResult> probe, IsolationLevel defaultLevel) {
            this.descriptor = IsolationExperiments.descriptor(id, name, description, defaultLevel);
            this.probe = probe;
        }

        @Override
        public ScenarioDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ScenarioReport execute(ExperimentConfiguration cfg, ExperimentContext ctx) {
            return report(cfg.isolationLevel(), List.of(probe.apply(ctx, cfg.isolationLevel())));
        }
    }
}

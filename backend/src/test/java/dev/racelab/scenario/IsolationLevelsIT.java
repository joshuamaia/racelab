package dev.racelab.scenario;

import dev.racelab.domain.model.ExperimentResult;
import dev.racelab.domain.model.IsolationLevel;
import dev.racelab.domain.model.Observation;
import dev.racelab.scenario.support.ConfigBuilder;
import dev.racelab.support.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static dev.racelab.domain.model.StrategyType.ISOLATION_LEVEL;
import static org.assertj.core.api.Assertions.assertThat;

/** The isolation probes must report exactly what PostgreSQL does — never a fabricated phenomenon. */
class IsolationLevelsIT extends IntegrationTest {

    @ParameterizedTest
    @EnumSource(IsolationLevel.class)
    void probesMatchPostgresSemantics(IsolationLevel level) {
        ExperimentResult r = run("isolation-probe", ISOLATION_LEVEL, ConfigBuilder.defaults().isolation(level).build());
        boolean snapshot = level == IsolationLevel.REPEATABLE_READ || level == IsolationLevel.SERIALIZABLE;

        assertThat(observation(r, "DIRTY_READ").reproduced()).as("dirty reads never happen in PostgreSQL").isFalse();
        assertThat(observation(r, "NON_REPEATABLE_READ").reproduced()).isEqualTo(!snapshot);
        assertThat(observation(r, "PHANTOM_READ").reproduced()).as("PostgreSQL RR prevents phantoms").isEqualTo(!snapshot);
        assertThat(observation(r, "DIRTY_READ").effectiveIsolationLevel()).isEqualTo(level.effectiveInPostgres());
        assertThat(r.violations()).isEmpty();
    }

    private static Observation observation(ExperimentResult r, String phenomenon) {
        return r.observations().stream().filter(o -> o.phenomenon().equals(phenomenon)).findFirst().orElseThrow();
    }
}

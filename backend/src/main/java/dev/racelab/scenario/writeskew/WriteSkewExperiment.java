package dev.racelab.scenario.writeskew;

import dev.racelab.application.engine.BlockRegistry;
import dev.racelab.application.engine.ConsistencyValidator;
import dev.racelab.application.engine.ExperimentContext;
import dev.racelab.application.engine.Invariant;
import dev.racelab.application.engine.ScenarioReport;
import dev.racelab.application.engine.TransactionCoordinator;
import dev.racelab.application.engine.TxSpec;
import dev.racelab.application.engine.WorkerRuntime;
import dev.racelab.application.engine.WorkerSpec;
import dev.racelab.domain.model.Category;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.IsolationLevel;
import dev.racelab.domain.model.Observation;
import dev.racelab.domain.model.ResourceState;
import dev.racelab.domain.model.ScenarioDescriptor;
import dev.racelab.domain.model.Severity;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.TxStatus;
import dev.racelab.domain.model.Violation;
import dev.racelab.domain.model.ViolationKind;
import dev.racelab.scenario.support.ConfigBuilder;
import dev.racelab.scenario.support.ScenarioSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.racelab.domain.model.StrategyType.*;

/**
 * Scenario 6 — write skew. Two on-call doctors; the rule requires at least one to stay on call. Each
 * transaction checks that two are available and takes a DIFFERENT doctor off call.
 */
@Component
public class WriteSkewExperiment extends ScenarioSupport {

    public static final String ID = "write-skew";
    static final String SHIFT = "NIGHT-01";

    // @snippet:start write-skew NONE,SERIALIZABLE,ADVISORY_LOCK sql "Count the doctors on call, then go off call"
    static final String COUNT_ON_CALL = "SELECT count(*) FROM doctor WHERE experiment_id = ? AND shift_code = ? AND on_call";
    static final String GO_OFF_CALL = "UPDATE doctor SET on_call = false, version = version + 1 WHERE id = ?";
    // @snippet:end

    // @snippet:start write-skew PESSIMISTIC_LOCKING sql "Materialize the conflict: lock every doctor of the shift"
    static final String LOCK_ON_CALL = """
            SELECT id FROM doctor WHERE experiment_id = ? AND shift_code = ? AND on_call ORDER BY id FOR UPDATE
            """;
    // @snippet:end

    // @snippet:start write-skew ADVISORY_LOCK sql "One advisory lock per shift"
    static final String SHIFT_LOCK = "SELECT pg_advisory_xact_lock(hashtext(?))";
    // @snippet:end

    public WriteSkewExperiment(JdbcTemplate jdbc, TransactionCoordinator coordinator, ConsistencyValidator validator) {
        super(jdbc, coordinator, validator);
    }

    @Override
    public ScenarioDescriptor descriptor() {
        return new ScenarioDescriptor(ID, "On-call doctors (write skew)", Category.CONCURRENCY,
                "Two transactions read the same rows and update different ones, breaking a rule that spans both rows.",
                "Dois médicos estão de plantão; a regra exige que pelo menos um permaneça disponível.",
                "doctors on call ≥ 1",
                List.of(NONE, SERIALIZABLE, PESSIMISTIC_LOCKING, ADVISORY_LOCK), NONE, true, false,
                List.of("threads", "isolationLevel", "delayAfterReadMs", "delayBeforeCommitMs", "retries", "executionMode"),
                Map.of("threads", "Doctors trying to go off call", "isolationLevel", "Isolation (NONE strategy)"),
                List.of(),
                ConfigBuilder.defaults().isolation(IsolationLevel.REPEATABLE_READ).initialValue("2").operationValue("1").build());
    }

    @Override
    public ScenarioReport execute(ExperimentConfiguration cfg, ExperimentContext ctx) {
        int doctors = Math.max(2, cfg.threads());
        List<Long> ids = ctx.setup(s -> {
            List<Long> seeded = new ArrayList<>(jdbc.queryForList("""
                    INSERT INTO doctor (experiment_id, name, shift_code, on_call)
                    SELECT ?, name, shift_code, on_call FROM doctor WHERE experiment_id = 'TEMPLATE' ORDER BY name
                    RETURNING id
                    """, Long.class, ctx.id()));
            for (int i = seeded.size(); i < doctors; i++) {
                seeded.add(jdbc.queryForObject("INSERT INTO doctor (experiment_id, name, shift_code, on_call) VALUES (?, ?, ?, true) RETURNING id",
                        Long.class, ctx.id(), "Médico " + (char) ('A' + i), SHIFT));
            }
            return seeded;
        });
        ctx.event(EventType.DATA_PREPARED, ids.size() + " doctors on call in shift " + SHIFT, m("doctorIds", ids));
        List<WorkerSpec> workers = new ArrayList<>();
        for (int i = 0; i < cfg.threads(); i++) {
            long doctorId = ids.get(i % ids.size());
            workers.add(WorkerSpec.local(i, rt -> List.of(goOffCall(rt, doctorId))));
        }
        List<TransactionOutcome> outcomes = ctx.runWorkers(workers);
        return report(ctx, ids.size(), outcomes);
    }

    // @snippet:start write-skew NONE,SERIALIZABLE,PESSIMISTIC_LOCKING,ADVISORY_LOCK java "Go off call if somebody else stays"
    TransactionOutcome goOffCall(WorkerRuntime rt, long doctorId) {
        IsolationLevel isolation = switch (rt.strategy()) {
            case SERIALIZABLE -> IsolationLevel.SERIALIZABLE;
            case ADVISORY_LOCK -> IsolationLevel.READ_COMMITTED; // the lock must precede the snapshot
            default -> rt.config().isolationLevel();
        };
        String resource = "shift:" + SHIFT;
        return coordinator.execute(rt, TxSpec.from(rt.config(), 1, 0, "off-call").withIsolation(isolation), tx -> {
            tx.detail("doctorId", doctorId);
            if (rt.strategy() == ADVISORY_LOCK) {
                tx.lock(BlockRegistry.Kind.DATABASE, resource, "pg_advisory_xact_lock(hashtext(shift))",
                        () -> tx.jdbc().queryForList(SHIFT_LOCK, rt.experimentId() + ":" + SHIFT));
            }
            long onCall = tx.read(resource, "count doctors on call", () -> rt.strategy() == PESSIMISTIC_LOCKING
                            ? tx.lock(BlockRegistry.Kind.DATABASE, resource, "SELECT … FOR UPDATE every on-call doctor",
                            () -> (long) tx.jdbc().queryForList(LOCK_ON_CALL, Long.class, rt.experimentId(), SHIFT).size())
                            : tx.jdbc().queryForObject(COUNT_ON_CALL, Long.class, rt.experimentId(), SHIFT),
                    n -> m("onCall", n));
            tx.before("onCall", onCall);
            tx.sync("after-read"); // both transactions saw the same number of doctors
            tx.validate(resource, "another doctor stays on call (onCall ≥ 2)", onCall >= 2, "LAST_DOCTOR",
                    "Cannot leave: would leave the shift without doctors");
            tx.write("doctor:" + doctorId, "UPDATE doctor SET on_call = false",
                    () -> tx.jdbc().update(GO_OFF_CALL, doctorId), n -> m("rowsAffected", n));
            tx.after("onCall", onCall - 1);
        });
    }
    // @snippet:end

    private ScenarioReport report(ExperimentContext ctx, int doctors, List<TransactionOutcome> outcomes) {
        long onCall = jdbc.queryForObject(COUNT_ON_CALL, Long.class, ctx.id(), SHIFT);
        long committed = count(outcomes, TxStatus.COMMITTED);
        List<Violation> violations = validator.validate(List.of(
                Invariant.rule("doctors on call ≥ 1")
                        .description("The shift must never be left without a doctor.")
                        .atLeast(1, onCall).severity(Severity.CRITICAL)
                        .cause("Write skew: each transaction validated the rule on its own snapshot and changed a different row.")
                        .recommend(SERIALIZABLE, PESSIMISTIC_LOCKING, ADVISORY_LOCK)
                        .transactions(committedIds(outcomes)).kind(ViolationKind.BUSINESS_INVARIANT).build()));
        IsolationLevel level = ctx.strategy() == SERIALIZABLE ? IsolationLevel.SERIALIZABLE
                : ctx.strategy() == ADVISORY_LOCK ? IsolationLevel.READ_COMMITTED : ctx.config().isolationLevel();
        Observation observation = new Observation("WRITE_SKEW", level, level.effectiveInPostgres(), !violations.isEmpty(),
                violations.isEmpty()
                        ? "Prevented: " + committed + " transaction(s) committed and " + onCall + " doctor(s) remain on call"
                        : "Reproduced: both transactions committed and no doctor is on call",
                m("onCall", onCall, "committed", committed),
                level == IsolationLevel.SERIALIZABLE
                        ? "SSI tracks the read/write dependency between the two transactions and aborts one with 40001."
                        : "Snapshot isolation (REPEATABLE READ) does not detect conflicts between DIFFERENT rows.");
        var rows = List.of(
                ConsistencyValidator.row("shift:" + SHIFT, "Plantão " + SHIFT, "doctors on call", doctors, onCall, "≥ 1", onCall >= 1),
                ConsistencyValidator.row("shift:" + SHIFT, "Plantão " + SHIFT, "transactions committed", 0, committed, null, true));
        String summary = violations.isEmpty()
                ? "Consistent: " + onCall + " doctor(s) still on call (" + level + ", " + ctx.strategy() + ")."
                : "WRITE SKEW: " + committed + " doctors went off call concurrently and nobody is on call (" + level + ").";
        return new ScenarioReport(summary,
                List.of(new ResourceState("shift:" + SHIFT, "Plantão " + SHIFT, "SHIFT", m("onCall", doctors))),
                List.of(new ResourceState("shift:" + SHIFT, "Plantão " + SHIFT, "SHIFT", m("onCall", onCall))),
                rows, violations, List.of(observation));
    }
}

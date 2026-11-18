package dev.racelab.scenario.seat;

import dev.racelab.application.engine.BlockRegistry;
import dev.racelab.application.engine.ConsistencyValidator;
import dev.racelab.application.engine.ExperimentContext;
import dev.racelab.application.engine.Invariant;
import dev.racelab.application.engine.ScenarioReport;
import dev.racelab.application.engine.TransactionCoordinator;
import dev.racelab.application.engine.TxScope;
import dev.racelab.application.engine.TxSpec;
import dev.racelab.application.engine.WorkerRuntime;
import dev.racelab.application.engine.WorkerSpec;
import dev.racelab.domain.model.Category;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.ResourceState;
import dev.racelab.domain.model.ScenarioDescriptor;
import dev.racelab.domain.model.ScenarioDescriptor.TemplateOption;
import dev.racelab.domain.model.Severity;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.Violation;
import dev.racelab.scenario.support.ConfigBuilder;
import dev.racelab.scenario.support.ScenarioSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.racelab.domain.model.StrategyType.*;

/** Scenario 3 — seat reservation: several customers try to book the same seat (double booking). */
@Component
public class SeatReservationExperiment extends ScenarioSupport {

    public static final String ID = "seat-reservation";

    static final String SEED = """
            INSERT INTO seat (experiment_id, code, status)
            SELECT ?, code, 'AVAILABLE' FROM seat WHERE experiment_id = 'TEMPLATE' AND code = ?
            RETURNING id
            """;

    // @snippet:start seat-reservation NONE sql "Check-then-act on a table without constraint"
    static final String COUNT_UNSAFE = "SELECT count(*) FROM reservation_unsafe WHERE experiment_id = ? AND seat_id = ?";
    static final String INSERT_UNSAFE = """
            INSERT INTO reservation_unsafe (experiment_id, seat_id, customer, transaction_id) VALUES (?, ?, ?, ?)
            """;
    // @snippet:end

    // @snippet:start seat-reservation UNIQUE_CONSTRAINT sql "Reservation table protected by UNIQUE (experiment_id, seat_id)"
    static final String COUNT_SAFE = "SELECT count(*) FROM reservation WHERE experiment_id = ? AND seat_id = ?";
    static final String INSERT_SAFE = """
            INSERT INTO reservation (experiment_id, seat_id, customer, transaction_id) VALUES (?, ?, ?, ?)
            """;
    // @snippet:end

    // @snippet:start seat-reservation PESSIMISTIC_LOCKING sql "Lock the seat row before checking"
    static final String LOCK_SEAT = "SELECT status FROM seat WHERE id = ? FOR UPDATE";
    // @snippet:end

    // @snippet:start seat-reservation ATOMIC_UPDATE sql "Atomic availability transition"
    static final String RESERVE_ATOMIC = """
            UPDATE seat SET status = 'RESERVED', reserved_by = ?, version = version + 1
             WHERE id = ? AND status = 'AVAILABLE'
            """;
    // @snippet:end

    static final String MARK_RESERVED = "UPDATE seat SET status = 'RESERVED', reserved_by = ?, version = version + 1 WHERE id = ?";

    public SeatReservationExperiment(JdbcTemplate jdbc, TransactionCoordinator coordinator, ConsistencyValidator validator) {
        super(jdbc, coordinator, validator);
    }

    @Override
    public ScenarioDescriptor descriptor() {
        return new ScenarioDescriptor(ID, "Seat reservation", Category.CONCURRENCY,
                "Several customers try to reserve the same seat at the same time.",
                "Dois usuários tentam reservar o mesmo assento.",
                "confirmed reservations per seat ≤ 1",
                List.of(NONE, UNIQUE_CONSTRAINT, PESSIMISTIC_LOCKING, ATOMIC_UPDATE), NONE, true, false,
                List.of("threads", "transactions", "resourceCode", "delayAfterReadMs", "delayBeforeCommitMs",
                        "lockTimeoutMs", "executionMode"),
                Map.of("threads", "Customers", "transactions", "Reservation attempts", "resourceCode", "Seat"),
                List.of(new TemplateOption("A01", "Assento A01"), new TemplateOption("A02", "Assento A02"),
                        new TemplateOption("A03", "Assento A03")),
                ConfigBuilder.defaults().resourceCode("A01").operationValue("1").initialValue("0").build());
    }

    @Override
    public ScenarioReport execute(ExperimentConfiguration cfg, ExperimentContext ctx) {
        String code = cfg.resourceCode() == null ? "A01" : cfg.resourceCode();
        long seatId = ctx.setup(s -> {
            List<Long> ids = jdbc.queryForList(SEED, Long.class, ctx.id(), code);
            return ids.isEmpty() ? jdbc.queryForObject("INSERT INTO seat (experiment_id, code) VALUES (?, ?) RETURNING id",
                    Long.class, ctx.id(), code) : ids.getFirst();
        });
        ctx.event(EventType.DATA_PREPARED, "Seat " + code + " available", m("seatId", seatId));
        List<WorkerSpec> workers = new ArrayList<>();
        for (int i = 0; i < cfg.threads(); i++) {
            workers.add(WorkerSpec.local(i, rt -> perOperation(rt, (op, round) -> reserve(rt, seatId, code, op, round))));
        }
        List<TransactionOutcome> outcomes = ctx.runWorkers(workers);
        return report(ctx, seatId, code, outcomes);
    }

    TransactionOutcome reserve(WorkerRuntime rt, long seatId, String code, int op, int round) {
        String customer = "customer-" + (rt.workerIndex() + 1);
        String resource = "seat:" + code;
        StrategyType strategy = rt.strategy();
        return coordinator.execute(rt, TxSpec.from(rt.config(), op, round, "reserve"), tx -> {
            tx.detail("customer", customer);
            switch (strategy) {
                case UNIQUE_CONSTRAINT -> checkThenInsert(tx, seatId, resource, customer, COUNT_SAFE, INSERT_SAFE);
                case PESSIMISTIC_LOCKING -> {
                    tx.lock(BlockRegistry.Kind.DATABASE, resource, "SELECT … FOR UPDATE on seat",
                            () -> tx.jdbc().queryForObject(LOCK_SEAT, String.class, seatId));
                    checkThenInsert(tx, seatId, resource, customer, COUNT_UNSAFE, INSERT_UNSAFE);
                }
                case ATOMIC_UPDATE -> atomic(tx, seatId, resource, customer);
                default -> checkThenInsert(tx, seatId, resource, customer, COUNT_UNSAFE, INSERT_UNSAFE);
            }
        });
    }

    // @snippet:start seat-reservation NONE,UNIQUE_CONSTRAINT,PESSIMISTIC_LOCKING java "Check availability, then insert the reservation"
    private void checkThenInsert(TxScope tx, long seatId, String resource, String customer, String countSql,
                                 String insertSql) {
        long existing = tx.read(resource, "count reservations for the seat",
                () -> tx.jdbc().queryForObject(countSql, Long.class, tx.runtime().experimentId(), seatId),
                n -> m("reservations", n));
        tx.sync("after-read");
        tx.validate(resource, "seat available", existing == 0, "SEAT_TAKEN", "Seat already reserved");
        tx.write(resource, "INSERT reservation for " + customer,
                () -> tx.jdbc().update(insertSql, tx.runtime().experimentId(), seatId, customer, tx.txId()),
                n -> m("rowsInserted", n)); // UNIQUE_CONSTRAINT: waits for the other insert, then 23505
        tx.jdbc().update(MARK_RESERVED, customer, seatId);
        tx.after("reservedBy", customer);
    }
    // @snippet:end

    // @snippet:start seat-reservation ATOMIC_UPDATE java "Atomic transition AVAILABLE → RESERVED"
    private void atomic(TxScope tx, long seatId, String resource, String customer) {
        String status = tx.read(resource, "SELECT status (informative)",
                () -> tx.jdbc().queryForObject("SELECT status FROM seat WHERE id = ?", String.class, seatId),
                s -> m("status", s));
        tx.before("status", status);
        tx.sync("after-read");
        int rows = tx.write(resource, "UPDATE seat SET status='RESERVED' WHERE status='AVAILABLE'",
                () -> tx.jdbc().update(RESERVE_ATOMIC, customer, seatId), n -> m("rowsAffected", n));
        tx.validate(resource, "rows affected = 1", rows == 1, "SEAT_TAKEN", "Seat was reserved by someone else");
        tx.jdbc().update(INSERT_UNSAFE, tx.runtime().experimentId(), seatId, customer, tx.txId());
        tx.after("reservedBy", customer);
    }
    // @snippet:end

    private ScenarioReport report(ExperimentContext ctx, long seatId, String code, List<TransactionOutcome> outcomes) {
        long reservations = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM reservation_unsafe WHERE experiment_id = ? AND seat_id = ?)
                     + (SELECT count(*) FROM reservation WHERE experiment_id = ? AND seat_id = ?)
                """, Long.class, ctx.id(), seatId, ctx.id(), seatId);
        List<String> customers = jdbc.queryForList("""
                SELECT customer FROM reservation_unsafe WHERE experiment_id = ? AND seat_id = ?
                UNION ALL SELECT customer FROM reservation WHERE experiment_id = ? AND seat_id = ?
                """, String.class, ctx.id(), seatId, ctx.id(), seatId);
        Map<String, Object> seat = jdbc.queryForMap("SELECT status, reserved_by, version FROM seat WHERE id = ?", seatId);
        List<Violation> violations = validator.validate(List.of(
                Invariant.rule("confirmed reservations per seat ≤ 1")
                        .description("A seat can be sold only once.")
                        .atMost(1, reservations).severity(Severity.CRITICAL)
                        .cause("Check-then-act: every customer checked availability before anyone inserted a reservation.")
                        .recommend(UNIQUE_CONSTRAINT, ATOMIC_UPDATE, PESSIMISTIC_LOCKING)
                        .transactions(committedIds(outcomes)).build()));
        String res = "seat:" + code;
        var rows = List.of(
                ConsistencyValidator.row(res, "Assento " + code, "confirmed reservations", 0, reservations, "≤ 1", reservations <= 1),
                ConsistencyValidator.row(res, "Assento " + code, "status", "AVAILABLE", seat.get("status"), "RESERVED",
                        reservations == 0 || "RESERVED".equals(seat.get("status"))),
                ConsistencyValidator.row(res, "Assento " + code, "holders", "—", String.join(", ", customers), "1 customer",
                        customers.size() <= 1));
        String summary = violations.isEmpty()
                ? "Consistent: seat " + code + " reserved by " + (customers.isEmpty() ? "nobody" : customers.getFirst()) + "."
                : "DOUBLE BOOKING: seat " + code + " was confirmed to " + customers.size() + " customers (" + String.join(", ", customers) + ").";
        return new ScenarioReport(summary,
                List.of(new ResourceState(res, "Assento " + code, "SEAT", m("status", "AVAILABLE", "reservations", 0))),
                List.of(new ResourceState(res, "Assento " + code, "SEAT", m("status", seat.get("status"),
                        "reservedBy", seat.get("reserved_by"), "reservations", reservations, "holders", customers))),
                rows, violations, List.of());
    }
}

package dev.racelab.scenario.orders;

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
import dev.racelab.domain.model.Severity;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.TxStatus;
import dev.racelab.domain.model.Violation;
import dev.racelab.scenario.support.ConfigBuilder;
import dev.racelab.scenario.support.ScenarioSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.racelab.domain.model.StrategyType.*;

/** Scenario 7 — order processing: several workers consume the same queue of pending orders. */
@Component
public class OrderProcessingExperiment extends ScenarioSupport {

    public static final String ID = "order-processing";

    // @snippet:start order-processing NONE,ATOMIC_CLAIM,IDEMPOTENCY_KEY sql "Pick the oldest pending order"
    static final String NEXT_PENDING = """
            SELECT id, code FROM work_item WHERE experiment_id = ? AND status = 'PENDING' ORDER BY id LIMIT 1
            """;
    // @snippet:end

    // @snippet:start order-processing SKIP_LOCKED sql "Queue semantics with SKIP LOCKED"
    static final String NEXT_SKIP_LOCKED = """
            SELECT id, code FROM work_item WHERE experiment_id = ? AND status = 'PENDING'
            ORDER BY id LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;
    // @snippet:end

    // @snippet:start order-processing ATOMIC_CLAIM sql "Atomic claim: exactly one owner"
    static final String CLAIM = """
            UPDATE work_item SET status = 'PROCESSING', owner = ?, claimed_at = clock_timestamp(), version = version + 1
             WHERE id = ? AND status = 'PENDING'
            """;
    // @snippet:end

    // @snippet:start order-processing IDEMPOTENCY_KEY sql "Idempotent consumer: remember what was processed"
    static final String MARK_PROCESSED = """
            INSERT INTO processed_event (experiment_id, event_id, consumer) VALUES (?, ?, 'shipping')
            ON CONFLICT (experiment_id, event_id, consumer) DO NOTHING
            """;
    // @snippet:end

    // @snippet:start order-processing * sql "Side effect and completion"
    static final String SHIP = """
            INSERT INTO side_effect (experiment_id, resource, description, worker_id, transaction_id) VALUES (?, ?, ?, ?, ?)
            """;
    static final String DONE = """
            UPDATE work_item SET status = 'DONE', owner = ?, completed_at = clock_timestamp(), version = version + 1 WHERE id = ?
            """;
    // @snippet:end

    record Item(long id, String code) {
    }

    public OrderProcessingExperiment(JdbcTemplate jdbc, TransactionCoordinator coordinator, ConsistencyValidator validator) {
        super(jdbc, coordinator, validator);
    }

    @Override
    public ScenarioDescriptor descriptor() {
        return new ScenarioDescriptor(ID, "Order processing workers", Category.DISTRIBUTED,
                "Several workers poll the same table of pending orders and ship them.",
                "Dois workers tentam processar o mesmo pedido.",
                "each order shipped exactly once ∧ every order processed",
                List.of(NONE, ATOMIC_CLAIM, SKIP_LOCKED, IDEMPOTENCY_KEY), NONE, false, false,
                List.of("threads", "resourceCount", "delayAfterReadMs", "delayBeforeCommitMs"),
                Map.of("threads", "Workers", "resourceCount", "Pending orders"),
                List.of(),
                ConfigBuilder.defaults().resourceCount(3).initialValue("0").operationValue("1").build());
    }

    @Override
    public ScenarioReport execute(ExperimentConfiguration cfg, ExperimentContext ctx) {
        int orders = cfg.resourceCount();
        ctx.setup(s -> {
            jdbc.update("""
                    INSERT INTO work_item (experiment_id, code, status)
                    SELECT ?, code, 'PENDING' FROM work_item WHERE experiment_id = 'TEMPLATE' ORDER BY code LIMIT ?
                    """, ctx.id(), orders);
            for (int i = 4; i <= orders; i++) {
                jdbc.update("INSERT INTO work_item (experiment_id, code, status) VALUES (?, ?, 'PENDING')", ctx.id(),
                        "PED-%03d".formatted(i));
            }
            return null;
        });
        ctx.event(EventType.DATA_PREPARED, orders + " pending orders", m("orders", orders));
        List<WorkerSpec> workers = new ArrayList<>();
        for (int i = 0; i < cfg.threads(); i++) {
            workers.add(WorkerSpec.local(i, rt -> consume(rt, orders)));
        }
        List<TransactionOutcome> outcomes = ctx.runWorkers(workers);
        return report(ctx, orders, outcomes);
    }

    List<TransactionOutcome> consume(WorkerRuntime rt, int orders) {
        List<TransactionOutcome> out = new ArrayList<>();
        for (int iteration = 0; iteration < orders * 3 + 2 && !rt.isCancelled(); iteration++) {
            Long pending = jdbc.queryForObject("SELECT count(*) FROM work_item WHERE experiment_id = ? AND status = 'PENDING'",
                    Long.class, rt.experimentId());
            if (pending == null || pending == 0) {
                break;
            }
            TransactionOutcome o = coordinator.execute(rt, TxSpec.from(rt.config(), iteration + 1, iteration, "process"),
                    tx -> processNext(tx, rt.strategy()));
            if (!Boolean.TRUE.equals(o.detail("empty"))) {
                out.add(o);
            }
        }
        return out;
    }

    // @snippet:start order-processing NONE,ATOMIC_CLAIM,SKIP_LOCKED,IDEMPOTENCY_KEY java "Worker loop body: pick, (claim), ship, complete"
    private void processNext(TxScope tx, StrategyType strategy) {
        String exp = tx.runtime().experimentId();
        List<Item> picked = tx.read("queue", strategy == SKIP_LOCKED ? "SELECT … FOR UPDATE SKIP LOCKED" : "SELECT oldest PENDING",
                () -> tx.jdbc().query(strategy == SKIP_LOCKED ? NEXT_SKIP_LOCKED : NEXT_PENDING,
                        (rs, i) -> new Item(rs.getLong("id"), rs.getString("code")), exp),
                items -> m("order", items.isEmpty() ? "none" : items.getFirst().code()));
        if (picked.isEmpty()) {
            tx.detail("empty", true);
            return;
        }
        Item item = picked.getFirst();
        String resource = "order:" + item.code();
        tx.detail("order", item.code());
        tx.sync("after-pick"); // every worker picked before anyone processes
        if (strategy == ATOMIC_CLAIM) {
            int claimed = tx.write(resource, "claim " + item.code(),
                    () -> tx.jdbc().update(CLAIM, tx.workerId(), item.id()), n -> m("rowsAffected", n));
            tx.validate(resource, "claim succeeded", claimed == 1, "ALREADY_CLAIMED", item.code() + " already claimed by another worker");
        }
        if (strategy == IDEMPOTENCY_KEY) {
            int first = tx.write(resource, "record processed " + item.code(),
                    () -> tx.jdbc().update(MARK_PROCESSED, exp, item.code()), n -> m("firstTime", n == 1));
            tx.validate(resource, "not processed before", first == 1, "DUPLICATE_MESSAGE",
                    item.code() + " was already processed — duplicate ignored");
        }
        tx.write(resource, "ship " + item.code(), () -> tx.jdbc().update(SHIP, exp, item.code(),
                "shipment for " + item.code(), tx.workerId(), tx.txId()), n -> m("shipments", n));
        tx.jdbc().update(DONE, tx.workerId(), item.id());
        tx.after("status", "DONE");
    }
    // @snippet:end

    private ScenarioReport report(ExperimentContext ctx, int orders, List<TransactionOutcome> outcomes) {
        List<Map<String, Object>> perOrder = jdbc.queryForList("""
                SELECT w.code, w.status, (SELECT count(*) FROM side_effect s WHERE s.experiment_id = w.experiment_id
                       AND s.resource = w.code) AS shipments
                FROM work_item w WHERE w.experiment_id = ? ORDER BY w.code
                """, ctx.id());
        long maxShipments = perOrder.stream().mapToLong(r -> asLong(r.get("shipments"))).max().orElse(0);
        long duplicates = perOrder.stream().mapToLong(r -> Math.max(0, asLong(r.get("shipments")) - 1)).sum();
        long notDone = perOrder.stream().filter(r -> !"DONE".equals(r.get("status"))).count();
        List<Violation> violations = validator.validate(List.of(
                Invariant.rule("each order shipped at most once").atMost(1, maxShipments).severity(Severity.HIGH)
                        .description("Shipping the same order twice costs money and confuses customers.")
                        .cause("Several workers selected the same pending order before anyone marked it.")
                        .recommend(SKIP_LOCKED, ATOMIC_CLAIM, IDEMPOTENCY_KEY).transactions(committedIds(outcomes)).build(),
                Invariant.rule("every order processed").equalTo(0, notDone).severity(Severity.MEDIUM)
                        .cause("Orders left pending.").build()));
        List<dev.racelab.domain.model.StateRow> rows = new ArrayList<>();
        for (Map<String, Object> r : perOrder) {
            long n = asLong(r.get("shipments"));
            rows.add(ConsistencyValidator.row("order:" + r.get("code"), (String) r.get("code"), "shipments", 0, n, "1", n == 1));
        }
        String summary = violations.isEmpty()
                ? "Consistent: " + orders + " orders shipped exactly once by " + ctx.config().threads() + " workers ("
                + count(outcomes, TxStatus.REJECTED) + " duplicate attempts rejected)."
                : "DUPLICATE PROCESSING: " + duplicates + " extra shipment(s) across " + orders + " orders.";
        return new ScenarioReport(summary,
                List.of(new ResourceState("queue", "Pedidos", "QUEUE", m("pending", orders))),
                List.of(new ResourceState("queue", "Pedidos", "QUEUE", m("pending", notDone, "duplicateShipments", duplicates))),
                rows, violations, List.of());
    }
}

package dev.racelab.scenario.inventory;

import dev.racelab.application.engine.BlockRegistry;
import dev.racelab.application.engine.ConsistencyValidator;
import dev.racelab.application.engine.ExperimentContext;
import dev.racelab.application.engine.Invariant;
import dev.racelab.application.engine.ScenarioExceptions.OptimisticConflictException;
import dev.racelab.application.engine.ScenarioReport;
import dev.racelab.application.engine.TransactionCoordinator;
import dev.racelab.application.engine.TxScope;
import dev.racelab.application.engine.TxSpec;
import dev.racelab.application.engine.WorkerRuntime;
import dev.racelab.application.engine.WorkerSpec;
import dev.racelab.domain.model.Category;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.IsolationLevel;
import dev.racelab.domain.model.ResourceState;
import dev.racelab.domain.model.ScenarioDescriptor;
import dev.racelab.domain.model.ScenarioDescriptor.TemplateOption;
import dev.racelab.domain.model.Severity;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.TxStatus;
import dev.racelab.domain.model.Violation;
import dev.racelab.scenario.support.ConfigBuilder;
import dev.racelab.scenario.support.ScenarioSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.racelab.domain.model.StrategyType.*;

/** Scenario 2 — inventory: concurrent purchases against a limited stock (overselling, lost update, negative stock). */
@Component
public class InventoryExperiment extends ScenarioSupport {

    public static final String ID = "inventory";

    static final String SEED = """
            INSERT INTO product (experiment_id, sku, name, stock)
            SELECT ?, sku, name, ? FROM product WHERE experiment_id = 'TEMPLATE' AND sku = ?
            RETURNING id
            """;

    // @snippet:start inventory NONE,SERIALIZABLE,PESSIMISTIC_LOCKING sql "Read stock and write the computed value"
    static final String SELECT_PRODUCT = "SELECT id, sku, name, stock, version FROM product WHERE id = ?";
    static final String SET_STOCK = "UPDATE product SET stock = ?, version = version + 1 WHERE id = ?";
    // @snippet:end

    // @snippet:start inventory PESSIMISTIC_LOCKING sql "Lock the product row"
    static final String SELECT_FOR_UPDATE = "SELECT id, sku, name, stock, version FROM product WHERE id = ? FOR UPDATE";
    // @snippet:end

    // @snippet:start inventory OPTIMISTIC_LOCKING sql "Version-checked update (JDBC optimistic locking)"
    static final String SET_STOCK_VERSIONED = """
            UPDATE product SET stock = ?, version = version + 1
             WHERE id = ? AND version = ?
            """;
    // @snippet:end

    // @snippet:start inventory ATOMIC_UNGUARDED sql "Atomic decrement WITHOUT guard"
    static final String DECREMENT_UNGUARDED = "UPDATE product SET stock = stock - ?, version = version + 1 WHERE id = ?";
    // @snippet:end

    // @snippet:start inventory ATOMIC_UPDATE sql "Atomic decrement guarded by the business rule"
    static final String DECREMENT_GUARDED = """
            UPDATE product SET stock = stock - ?, version = version + 1
             WHERE id = ? AND stock >= ?
            """;
    // @snippet:end

    // @snippet:start inventory * sql "Order and item recorded in the same transaction"
    static final String INSERT_ORDER = """
            INSERT INTO purchase_order (experiment_id, order_number, status, transaction_id, worker_id)
            VALUES (?, ?, 'CONFIRMED', ?, ?) RETURNING id
            """;
    static final String INSERT_ITEM = """
            INSERT INTO purchase_order_item (experiment_id, order_id, product_id, quantity) VALUES (?, ?, ?, ?)
            """;
    // @snippet:end

    record Product(long id, String sku, String name, int stock, long version) {
    }

    static final RowMapper<Product> MAPPER = (rs, i) -> new Product(rs.getLong("id"), rs.getString("sku"),
            rs.getString("name"), rs.getInt("stock"), rs.getLong("version"));

    public InventoryExperiment(JdbcTemplate jdbc, TransactionCoordinator coordinator, ConsistencyValidator validator) {
        super(jdbc, coordinator, validator);
    }

    @Override
    public ScenarioDescriptor descriptor() {
        return new ScenarioDescriptor(ID, "Concurrent purchases (inventory)", Category.CONCURRENCY,
                "Several purchases compete for a limited stock.",
                "Estoque inicial de 10 unidades; duas compras simultâneas de 7 unidades cada.",
                "initial stock − confirmed quantities = final stock ∧ stock ≥ 0",
                List.of(NONE, ATOMIC_UNGUARDED, OPTIMISTIC_LOCKING, PESSIMISTIC_LOCKING, ATOMIC_UPDATE, SERIALIZABLE),
                NONE, true, false,
                List.of("threads", "transactions", "initialValue", "operationValue", "resourceCode", "isolationLevel",
                        "delayAfterReadMs", "delayBeforeCommitMs", "retries", "lockTimeoutMs", "executionMode"),
                Map.of("initialValue", "Initial stock (units)", "operationValue", "Quantity per purchase",
                        "resourceCode", "Product template", "transactions", "Purchases (total)"),
                List.of(new TemplateOption("NOTEBOOK", "Notebook — 10 unidades"),
                        new TemplateOption("MONITOR", "Monitor — 5 unidades"),
                        new TemplateOption("KEYBOARD", "Teclado — 20 unidades")),
                ConfigBuilder.defaults().initialValue("10").operationValue("7").resourceCode("NOTEBOOK").build());
    }

    @Override
    public ScenarioReport execute(ExperimentConfiguration cfg, ExperimentContext ctx) {
        String sku = cfg.resourceCode() == null ? "NOTEBOOK" : cfg.resourceCode();
        int initialStock = cfg.initialValue().intValue();
        long productId = ctx.setup(s -> {
            List<Long> ids = jdbc.queryForList(SEED, Long.class, ctx.id(), initialStock, sku);
            return ids.isEmpty() ? jdbc.queryForObject(
                    "INSERT INTO product (experiment_id, sku, name, stock) VALUES (?, ?, ?, ?) RETURNING id",
                    Long.class, ctx.id(), sku, sku, initialStock) : ids.getFirst();
        });
        Product initial = jdbc.queryForObject(SELECT_PRODUCT, MAPPER, productId);
        ctx.event(EventType.DATA_PREPARED, "Product " + sku + " seeded with stock " + initialStock,
                m("productId", productId, "stock", initialStock));
        List<WorkerSpec> workers = new ArrayList<>();
        for (int i = 0; i < cfg.threads(); i++) {
            workers.add(WorkerSpec.local(i, rt -> perOperation(rt, (op, round) -> purchase(rt, productId, sku, op, round))));
        }
        List<TransactionOutcome> outcomes = ctx.runWorkers(workers);
        return report(ctx, initial, outcomes);
    }

    TransactionOutcome purchase(WorkerRuntime rt, long productId, String sku, int op, int round) {
        StrategyType strategy = rt.strategy();
        TxSpec spec = TxSpec.from(rt.config(), op, round, "purchase");
        if (strategy == SERIALIZABLE) {
            spec = spec.withIsolation(IsolationLevel.SERIALIZABLE);
        }
        int qty = rt.config().quantity();
        String resource = "product:" + sku;
        return coordinator.execute(rt, spec, tx -> {
            tx.detail("quantity", qty);
            switch (strategy) {
                case ATOMIC_UPDATE -> guarded(tx, productId, resource, qty);
                case OPTIMISTIC_LOCKING -> optimistic(tx, productId, resource, qty);
                default -> readCheckWrite(tx, productId, resource, qty, strategy);
            }
            long orderId = tx.jdbc().queryForObject(INSERT_ORDER, Long.class, rt.experimentId(),
                    "ORD-" + tx.txId(), tx.txId(), tx.workerId());
            tx.jdbc().update(INSERT_ITEM, rt.experimentId(), orderId, productId, qty);
            tx.detail("orderId", orderId);
        });
    }

    // @snippet:start inventory NONE,SERIALIZABLE,PESSIMISTIC_LOCKING,ATOMIC_UNGUARDED java "Check stock, then decrement"
    private void readCheckWrite(TxScope tx, long productId, String resource, int qty, StrategyType strategy) {
        boolean lock = strategy == PESSIMISTIC_LOCKING;
        Product p = tx.read(resource, lock ? "SELECT … FOR UPDATE" : "SELECT stock",
                () -> lock
                        ? tx.lock(BlockRegistry.Kind.DATABASE, resource, "row lock on product",
                        () -> tx.jdbc().queryForObject(SELECT_FOR_UPDATE, MAPPER, productId))
                        : tx.jdbc().queryForObject(SELECT_PRODUCT, MAPPER, productId),
                v -> m("stock", v.stock(), "version", v.version()));
        tx.before("stock", p.stock());
        tx.sync("after-read");
        tx.validate(resource, "stock ≥ " + qty, p.stock() >= qty, "OUT_OF_STOCK",
                "Insufficient stock: " + p.stock() + " < " + qty);
        if (strategy == ATOMIC_UNGUARDED) {
            tx.write(resource, "UPDATE product SET stock = stock - " + qty,
                    () -> tx.jdbc().update(DECREMENT_UNGUARDED, qty, productId), n -> m("rowsAffected", n));
        } else {
            int newStock = p.stock() - qty; // computed from the value read above
            tx.write(resource, "UPDATE product SET stock = " + newStock,
                    () -> tx.jdbc().update(SET_STOCK, newStock, productId), n -> m("rowsAffected", n, "stock", newStock));
        }
        tx.after("stock", tx.jdbc().queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, productId));
    }
    // @snippet:end

    // @snippet:start inventory OPTIMISTIC_LOCKING java "Version check with JDBC: 0 rows = conflict"
    private void optimistic(TxScope tx, long productId, String resource, int qty) {
        Product p = tx.read(resource, "SELECT stock, version",
                () -> tx.jdbc().queryForObject(SELECT_PRODUCT, MAPPER, productId),
                v -> m("stock", v.stock(), "version", v.version()));
        tx.before("stock", p.stock());
        tx.sync("after-read");
        tx.validate(resource, "stock ≥ " + qty, p.stock() >= qty, "OUT_OF_STOCK",
                "Insufficient stock: " + p.stock() + " < " + qty);
        int rows = tx.write(resource, "UPDATE … WHERE version = " + p.version(),
                () -> tx.jdbc().update(SET_STOCK_VERSIONED, p.stock() - qty, productId, p.version()),
                n -> m("rowsAffected", n));
        if (rows == 0) {
            throw new OptimisticConflictException("product " + productId + " changed after version " + p.version() + " was read");
        }
        tx.after("stock", p.stock() - qty);
    }
    // @snippet:end

    // @snippet:start inventory ATOMIC_UPDATE java "Guarded atomic decrement"
    private void guarded(TxScope tx, long productId, String resource, int qty) {
        Product seen = tx.read(resource, "SELECT stock (informative)",
                () -> tx.jdbc().queryForObject(SELECT_PRODUCT, MAPPER, productId), v -> m("stock", v.stock()));
        tx.before("stock", seen.stock());
        tx.sync("after-read");
        int rows = tx.write(resource, "UPDATE … SET stock = stock - " + qty + " WHERE stock >= " + qty,
                () -> tx.jdbc().update(DECREMENT_GUARDED, qty, productId, qty), n -> m("rowsAffected", n));
        tx.validate(resource, "rows affected = 1", rows == 1, "OUT_OF_STOCK",
                "Guarded update affected 0 rows: not enough stock at write time");
        tx.after("stock", tx.jdbc().queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, productId));
    }
    // @snippet:end

    private ScenarioReport report(ExperimentContext ctx, Product initial, List<TransactionOutcome> outcomes) {
        Product fin = jdbc.queryForObject(SELECT_PRODUCT, MAPPER, initial.id());
        Map<String, Object> sold = jdbc.queryForMap("""
                SELECT count(DISTINCT i.order_id) AS orders, coalesce(sum(i.quantity), 0) AS units
                FROM purchase_order_item i WHERE i.experiment_id = ? AND i.product_id = ?
                """, ctx.id(), initial.id());
        long orders = asLong(sold.get("orders"));
        long units = asLong(sold.get("units"));
        long expectedStock = initial.stock() - units;
        List<String> committed = committedIds(outcomes);
        List<Violation> violations = validator.validate(List.of(
                Invariant.rule("initial stock − confirmed quantities = final stock")
                        .description("Every confirmed order must be reflected in the stock.")
                        .equalTo(expectedStock, fin.stock()).severity(Severity.CRITICAL)
                        .cause("Lost update: purchases computed the new stock from the same stale read.")
                        .recommend(ATOMIC_UPDATE, OPTIMISTIC_LOCKING, PESSIMISTIC_LOCKING).transactions(committed).build(),
                Invariant.rule("confirmed quantities ≤ initial stock")
                        .description("Never sell more units than available.")
                        .atMost(initial.stock(), units).severity(Severity.CRITICAL)
                        .cause("Overselling: every purchase validated the stock before the others decremented it.")
                        .recommend(ATOMIC_UPDATE, PESSIMISTIC_LOCKING).transactions(committed).build(),
                Invariant.rule("stock ≥ 0")
                        .atLeast(0, fin.stock()).severity(Severity.HIGH)
                        .cause("Decrement applied without a guard in the UPDATE.")
                        .recommend(ATOMIC_UPDATE).transactions(committed).build()));
        String res = "product:" + initial.sku();
        var rows = List.of(
                ConsistencyValidator.row(res, initial.name(), "stock", initial.stock(), fin.stock(), expectedStock),
                ConsistencyValidator.row(res, initial.name(), "units sold", 0, units, "≤ " + initial.stock(), units <= initial.stock()),
                ConsistencyValidator.row(res, initial.name(), "confirmed orders", 0, orders,
                        String.valueOf(count(outcomes, TxStatus.COMMITTED)), orders == count(outcomes, TxStatus.COMMITTED)),
                ConsistencyValidator.row(res, initial.name(), "version", initial.version(), fin.version(), null, true));
        String summary = violations.isEmpty()
                ? "Consistent: " + orders + " order(s) confirmed (" + units + " units); final stock " + fin.stock() + "."
                : "INCONSISTENT: " + orders + " orders confirmed for " + units + " units with an initial stock of "
                + initial.stock() + "; final stock " + fin.stock() + " (expected " + expectedStock + ").";
        return new ScenarioReport(summary, List.of(state(initial)), List.of(state(fin)), rows, violations, List.of());
    }

    private static ResourceState state(Product p) {
        return new ResourceState("product:" + p.sku(), p.name(), "PRODUCT", m("stock", p.stock(), "version", p.version()));
    }
}

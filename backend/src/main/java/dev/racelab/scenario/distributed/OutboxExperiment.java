package dev.racelab.scenario.distributed;

import dev.racelab.application.engine.ConsistencyValidator;
import dev.racelab.application.engine.ExperimentContext;
import dev.racelab.application.engine.Invariant;
import dev.racelab.application.engine.ScenarioExceptions.InjectedFault;
import dev.racelab.application.engine.ScenarioReport;
import dev.racelab.application.engine.TransactionCoordinator;
import dev.racelab.application.engine.TxSpec;
import dev.racelab.application.engine.WorkerRuntime;
import dev.racelab.application.engine.WorkerSpec;
import dev.racelab.application.port.MessageBroker;
import dev.racelab.domain.model.Category;
import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.FailureMode;
import dev.racelab.domain.model.ResourceState;
import dev.racelab.domain.model.ScenarioDescriptor;
import dev.racelab.domain.model.Severity;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.Violation;
import dev.racelab.scenario.support.ConfigBuilder;
import dev.racelab.scenario.support.ScenarioSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.racelab.domain.model.StrategyType.*;

/**
 * Transactional outbox. The order service must store an order AND notify downstream services. Dual writes
 * (DB commit + publish) lose or invent events when the process crashes in between; the outbox stores the
 * event in the same transaction and a relay publishes it with at-least-once semantics. Crashes are injected
 * faults (labelled FAULT_INJECTED), never fake database errors.
 */
@Component
public class OutboxExperiment extends ScenarioSupport {

    public static final String ID = "outbox";

    static final String INSERT_ORDER = """
            INSERT INTO purchase_order (experiment_id, order_number, status, amount, transaction_id, worker_id)
            VALUES (?, ?, 'CREATED', ?, ?, ?)
            """;

    // @snippet:start outbox OUTBOX,OUTBOX_NAIVE_CONSUMER sql "Outbox: event stored in the order transaction; relay claims with SKIP LOCKED"
    static final String INSERT_OUTBOX = """
            INSERT INTO outbox_event (experiment_id, aggregate_id, event_type, payload)
            VALUES (?, ?, 'OrderCreated', ?::jsonb)
            """;
    static final String CLAIM_PENDING = """
            SELECT id, aggregate_id, payload::text AS payload FROM outbox_event
             WHERE experiment_id = ? AND status = 'PENDING'
             ORDER BY id LIMIT 1
             FOR UPDATE SKIP LOCKED
            """;
    static final String MARK_PUBLISHED = """
            UPDATE outbox_event SET status = 'PUBLISHED', attempts = attempts + 1, published_by = ?, published_at = clock_timestamp()
             WHERE id = ?
            """;
    // @snippet:end

    // @snippet:start outbox OUTBOX sql "Idempotent consumer"
    static final String DEDUP = """
            INSERT INTO processed_event (experiment_id, event_id, consumer) VALUES (?, ?, 'billing')
            ON CONFLICT (experiment_id, event_id, consumer) DO NOTHING
            """;
    // @snippet:end

    static final String EFFECT = """
            INSERT INTO side_effect (experiment_id, resource, description, worker_id, transaction_id) VALUES (?, ?, ?, ?, ?)
            """;

    private final MessageBroker broker;

    public OutboxExperiment(JdbcTemplate jdbc, TransactionCoordinator coordinator, ConsistencyValidator validator,
                            MessageBroker broker) {
        super(jdbc, coordinator, validator);
        this.broker = broker;
    }

    @Override
    public ScenarioDescriptor descriptor() {
        return new ScenarioDescriptor(ID, "Order events (transactional outbox)", Category.DISTRIBUTED,
                "An order service stores orders and must publish an OrderCreated event for a billing consumer.",
                "Processamento de pedidos: gravar o pedido e publicar o evento, com falhas entre as operações.",
                "every committed order announced exactly once to billing ∧ no event for a non-existent order",
                List.of(NONE, OUTBOX, OUTBOX_NAIVE_CONSUMER), OUTBOX, false, false,
                List.of("transactions", "threads", "failureMode", "operationValue"),
                Map.of("transactions", "Orders", "threads", "Outbox relays", "failureMode", "Injected crash",
                        "operationValue", "Order amount"),
                List.of(),
                ConfigBuilder.defaults().transactions(3).threads(2).operationValue("99.90").initialValue("0")
                        .failureMode(FailureMode.RELAY_CRASH_BEFORE_ACK).build());
    }

    @Override
    public ScenarioReport execute(ExperimentConfiguration cfg, ExperimentContext ctx) {
        String topic = "racelab:outbox:" + ctx.id();
        ctx.event(EventType.DATA_PREPARED, "Topic " + topic + " on " + broker.name() + "; failure mode " + cfg.failureMode(),
                m("topic", topic, "broker", broker.name(), "failureMode", cfg.failureMode().name()));
        boolean outbox = ctx.strategy() != NONE;
        int relays = outbox ? Math.max(1, cfg.threads()) : 0;
        CountDownLatch ordersDone = new CountDownLatch(1);
        CountDownLatch relaysDone = new CountDownLatch(relays);
        AtomicBoolean relayFaulted = new AtomicBoolean();
        List<WorkerSpec> workers = new ArrayList<>();
        workers.add(WorkerSpec.local("order-service", 0, rt -> {
            try {
                return createOrders(rt, topic);
            } finally {
                ordersDone.countDown();
            }
        }));
        for (int i = 0; i < relays; i++) {
            workers.add(WorkerSpec.local("relay-" + (i + 1), i + 1, rt -> {
                try {
                    return relay(rt, topic, ordersDone, relayFaulted);
                } finally {
                    relaysDone.countDown();
                }
            }));
        }
        workers.add(WorkerSpec.local("billing-consumer", relays + 1, rt -> consume(rt, topic, ordersDone, relaysDone)));
        List<TransactionOutcome> outcomes = ctx.runWorkers(workers);
        return report(ctx, topic, outcomes);
    }

    // @snippet:start outbox NONE,OUTBOX,OUTBOX_NAIVE_CONSUMER java "Order service: dual write vs outbox"
    List<TransactionOutcome> createOrders(WorkerRuntime rt, String topic) {
        List<TransactionOutcome> out = new ArrayList<>();
        FailureMode fault = rt.config().failureMode();
        for (int i = 1; i <= rt.config().transactions(); i++) {
            String order = "ORD-%03d".formatted(i);
            boolean crashHere = i == 1;
            TransactionOutcome o = coordinator.execute(rt, TxSpec.from(rt.config(), i, i - 1, "create-order").withRetries(0), tx -> {
                tx.detail("order", order);
                tx.write("order:" + order, "INSERT order " + order, () -> tx.jdbc().update(INSERT_ORDER, rt.experimentId(),
                        order, rt.config().operationValue(), tx.txId(), tx.workerId()), n -> m("rows", n));
                if (rt.strategy() != NONE) {
                    tx.write("outbox", "INSERT outbox_event OrderCreated(" + order + ") — same transaction",
                            () -> tx.jdbc().update(INSERT_OUTBOX, rt.experimentId(), order, "{\"order\":\"" + order + "\"}"),
                            n -> m("rows", n));
                } else if (fault == FailureMode.CRASH_BEFORE_COMMIT && crashHere) {
                    publish(rt, topic, order, tx.txId());          // dual write: event leaves BEFORE the commit…
                    throw new InjectedFault("process crashed before COMMIT — the order is rolled back but its event is out");
                }
            });
            out.add(o);
            if (!o.committed()) {
                continue;
            }
            if (fault == FailureMode.CRASH_AFTER_COMMIT && crashHere) {
                rt.emit(o.transactionId(), null, EventType.FAULT_INJECTED, EventSource.INSTRUMENTED, "order:" + order,
                        rt.strategy() == NONE
                                ? "Process crashed after COMMIT and before publishing: the event for " + order + " is lost"
                                : "Process crashed after COMMIT: the event is safe in the outbox table",
                        m("order", order));
                continue;
            }
            if (rt.strategy() == NONE) {
                publish(rt, topic, order, o.transactionId());   // dual write: publish after the commit
            }
        }
        return out;
    }
    // @snippet:end

    // @snippet:start outbox OUTBOX,OUTBOX_NAIVE_CONSUMER java "Relay: claim a pending event, publish, mark published"
    List<TransactionOutcome> relay(WorkerRuntime rt, String topic, CountDownLatch ordersDone, AtomicBoolean faulted)
            throws InterruptedException {
        List<TransactionOutcome> out = new ArrayList<>();
        for (int iteration = 1; iteration <= 500 && !rt.isCancelled(); iteration++) {
            Long pending = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE experiment_id = ? AND status = 'PENDING'",
                    Long.class, rt.experimentId());
            if (pending == null || pending == 0) {
                if (ordersDone.await(20, TimeUnit.MILLISECONDS)) {
                    Long again = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE experiment_id = ? AND status = 'PENDING'",
                            Long.class, rt.experimentId());
                    if (again == null || again == 0) {
                        break;
                    }
                }
                continue;
            }
            TransactionOutcome o = coordinator.execute(rt, TxSpec.from(rt.config(), iteration, iteration, "relay").withRetries(0), tx -> {
                List<Map<String, Object>> claimed = tx.read("outbox", "SELECT … FOR UPDATE SKIP LOCKED",
                        () -> tx.jdbc().queryForList(CLAIM_PENDING, rt.experimentId()),
                        rows -> m("claimed", rows.isEmpty() ? "none" : rows.getFirst().get("aggregate_id")));
                if (claimed.isEmpty()) {
                    tx.detail("empty", true);
                    return;
                }
                long eventId = ((Number) claimed.getFirst().get("id")).longValue();
                String order = (String) claimed.getFirst().get("aggregate_id");
                tx.detail("order", order).detail("outboxId", eventId);
                publish(rt, topic, order, tx.txId());
                if (rt.config().failureMode() == FailureMode.RELAY_CRASH_BEFORE_ACK && faulted.compareAndSet(false, true)) {
                    throw new InjectedFault("relay crashed after publishing " + order + " and before marking it PUBLISHED");
                }
                tx.write("outbox", "mark " + order + " PUBLISHED", () -> tx.jdbc().update(MARK_PUBLISHED, tx.workerId(), eventId),
                        n -> m("rows", n));
            });
            if (!Boolean.TRUE.equals(o.detail("empty"))) {
                out.add(o);
            }
        }
        return out;
    }
    // @snippet:end

    // @snippet:start outbox NONE,OUTBOX,OUTBOX_NAIVE_CONSUMER java "Billing consumer: idempotent (OUTBOX) or naive"
    List<TransactionOutcome> consume(WorkerRuntime rt, String topic, CountDownLatch ordersDone, CountDownLatch relaysDone)
            throws InterruptedException {
        List<TransactionOutcome> out = new ArrayList<>();
        String last = "0-0";
        int op = 0;
        while (!rt.isCancelled()) {
            boolean producersDone = ordersDone.getCount() == 0 && relaysDone.getCount() == 0;
            List<MessageBroker.Message> batch = broker.read(topic, last, 100);
            if (batch.isEmpty()) {
                if (producersDone) {
                    break;
                }
                Thread.sleep(20);
                continue;
            }
            for (MessageBroker.Message msg : batch) {
                last = msg.id();
                String order = msg.body().get("order");
                rt.emit(null, null, EventType.MESSAGE_CONSUMED, EventSource.INSTRUMENTED, "order:" + order,
                        "Received OrderCreated(" + order + ") — message " + msg.id(), m("messageId", msg.id(), "order", order));
                out.add(coordinator.execute(rt, TxSpec.from(rt.config(), ++op, op, "bill").withRetries(0), tx -> {
                    tx.detail("order", order).detail("messageId", msg.id());
                    if (rt.strategy() == OUTBOX) {
                        int first = tx.write("billing", "record processed OrderCreated(" + order + ")",
                                () -> tx.jdbc().update(DEDUP, rt.experimentId(), order), n -> m("firstDelivery", n == 1));
                        tx.validate("billing", "event not processed before", first == 1, "DUPLICATE_EVENT",
                                "Duplicate delivery of " + order + " ignored");
                    }
                    tx.write("billing", "issue invoice for " + order, () -> tx.jdbc().update(EFFECT, rt.experimentId(),
                            order, "invoice for " + order, tx.workerId(), tx.txId()), n -> m("invoices", n));
                }));
            }
        }
        return out;
    }
    // @snippet:end

    private void publish(WorkerRuntime rt, String topic, String order, String txId) {
        String id = broker.publish(topic, Map.of("order", order, "type", "OrderCreated", "publishedBy", rt.workerId()));
        rt.emit(txId, null, EventType.MESSAGE_PUBLISHED, EventSource.INSTRUMENTED, "topic",
                "Published OrderCreated(" + order + ") as message " + id + " on " + broker.name(), m("messageId", id, "order", order));
    }

    private ScenarioReport report(ExperimentContext ctx, String topic, List<TransactionOutcome> outcomes) {
        List<String> orders = jdbc.queryForList("SELECT order_number FROM purchase_order WHERE experiment_id = ? ORDER BY 1",
                String.class, ctx.id());
        List<MessageBroker.Message> deliveries = broker.read(topic, "0-0", 10_000);
        Set<String> announced = new HashSet<>();
        long ghosts = 0;
        for (MessageBroker.Message msg : deliveries) {
            String order = msg.body().get("order");
            if (orders.contains(order)) {
                announced.add(order);
            } else {
                ghosts++;
            }
        }
        long duplicates = deliveries.size() - announced.size() - ghosts;
        long maxInvoices = jdbc.query("""
                SELECT coalesce(max(c), 0) FROM (SELECT count(*) c FROM side_effect WHERE experiment_id = ? GROUP BY resource) t
                """, rs -> rs.next() ? rs.getLong(1) : 0L, ctx.id());
        long invoices = jdbc.queryForObject("SELECT count(*) FROM side_effect WHERE experiment_id = ?", Long.class, ctx.id());
        Map<String, Object> outbox = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending, count(*) FILTER (WHERE status = 'PUBLISHED') AS published
                FROM outbox_event WHERE experiment_id = ?""", ctx.id());
        List<String> committed = committedIds(outcomes);
        List<Violation> violations = validator.validate(List.of(
                Invariant.rule("every committed order is announced").equalTo(orders.size(), announced.size())
                        .severity(Severity.CRITICAL).description("Downstream services must hear about every order.")
                        .cause("Dual write: the process died between the database commit and the publication.")
                        .recommend(OUTBOX).transactions(committed).build(),
                Invariant.rule("no event for a non-existent order").equalTo(0, ghosts).severity(Severity.HIGH)
                        .cause("Dual write: the event was published before a commit that never happened.")
                        .recommend(OUTBOX).build(),
                Invariant.rule("each event processed at most once by billing").atMost(1, maxInvoices)
                        .severity(Severity.HIGH).description("A redelivered event must not create a second invoice.")
                        .cause("At-least-once delivery + non-idempotent consumer.")
                        .recommend(OUTBOX).transactions(committed).build()));
        var rows = List.of(
                ConsistencyValidator.row("orders", "Pedidos", "orders committed", 0, orders.size(), null, true),
                ConsistencyValidator.row("topic", "Broker (" + broker.name() + ")", "messages delivered", 0, deliveries.size(), null, true),
                ConsistencyValidator.row("topic", "Broker", "orders announced", 0, announced.size(), orders.size()),
                ConsistencyValidator.row("topic", "Broker", "duplicate deliveries", 0, duplicates, null, true),
                ConsistencyValidator.row("topic", "Broker", "ghost events", 0, ghosts, 0),
                ConsistencyValidator.row("outbox", "Outbox", "pending / published", "0 / 0",
                        outbox.get("pending") + " / " + outbox.get("published"), null, asLong(outbox.get("pending")) == 0),
                ConsistencyValidator.row("billing", "Billing", "invoices", 0, invoices, orders.size()));
        String summary = violations.isEmpty()
                ? "Consistent: " + orders.size() + " orders, " + deliveries.size() + " deliveries (" + duplicates
                + " duplicate), " + invoices + " invoices — at-least-once delivery, effectively-once processing."
                : "INCONSISTENT: " + orders.size() + " orders, " + announced.size() + " announced, " + ghosts + " ghost event(s), "
                + invoices + " invoices for " + deliveries.size() + " deliveries.";
        return new ScenarioReport(summary,
                List.of(new ResourceState("orders", "Pedidos", "ORDERS", m("orders", 0, "events", 0))),
                List.of(new ResourceState("orders", "Pedidos", "ORDERS", m("orders", orders.size(), "deliveries",
                        deliveries.size(), "invoices", invoices, "outboxPending", outbox.get("pending")))),
                rows, violations, List.of());
    }
}

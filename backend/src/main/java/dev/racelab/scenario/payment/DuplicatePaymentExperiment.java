package dev.racelab.scenario.payment;

import dev.racelab.application.engine.ConsistencyValidator;
import dev.racelab.application.engine.ExperimentContext;
import dev.racelab.application.engine.Invariant;
import dev.racelab.application.engine.ScenarioExceptions.BusinessRuleRejection;
import dev.racelab.application.engine.ScenarioReport;
import dev.racelab.application.engine.TransactionCoordinator;
import dev.racelab.application.engine.TxScope;
import dev.racelab.application.engine.TxSpec;
import dev.racelab.application.engine.WorkerRuntime;
import dev.racelab.application.engine.WorkerSpec;
import dev.racelab.domain.model.Category;
import dev.racelab.domain.model.EventSource;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.ResourceState;
import dev.racelab.domain.model.ScenarioDescriptor;
import dev.racelab.domain.model.Severity;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.Violation;
import dev.racelab.scenario.support.ConfigBuilder;
import dev.racelab.scenario.support.ScenarioSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.racelab.domain.model.StrategyType.*;

/**
 * Scenario 4 — duplicate payment. The client sends a payment; its response times out while the server is
 * still processing, so the client retries with the same request. Without idempotency the customer is
 * charged twice.
 */
@Component
public class DuplicatePaymentExperiment extends ScenarioSupport {

    public static final String ID = "duplicate-payment";

    // @snippet:start duplicate-payment NONE sql "Payment table without uniqueness"
    static final String INSERT_UNSAFE = """
            INSERT INTO payment_unsafe (experiment_id, idempotency_key, customer, amount, status, transaction_id, worker_id)
            VALUES (?, ?, ?, ?, 'CAPTURED', ?, ?) RETURNING id
            """;
    // @snippet:end

    // @snippet:start duplicate-payment UNIQUE_CONSTRAINT,IDEMPOTENCY_KEY sql "Payment with UNIQUE (experiment_id, idempotency_key)"
    static final String INSERT_PAYMENT = """
            INSERT INTO payment (experiment_id, idempotency_key, customer, amount, status, transaction_id, worker_id)
            VALUES (?, ?, ?, ?, 'CAPTURED', ?, ?) RETURNING id
            """;
    // @snippet:end

    // @snippet:start duplicate-payment IDEMPOTENCY_KEY sql "Idempotency record: claim the key, store the response"
    static final String CLAIM_KEY = """
            INSERT INTO idempotency_record (experiment_id, idempotency_key, request_hash, status)
            VALUES (?, ?, ?, 'PROCESSING')
            ON CONFLICT (experiment_id, idempotency_key) DO NOTHING
            RETURNING id
            """;
    static final String COMPLETE_KEY = """
            UPDATE idempotency_record
               SET status = 'COMPLETED', payment_id = ?, response = ?::jsonb, completed_at = clock_timestamp()
             WHERE experiment_id = ? AND idempotency_key = ?
            """;
    static final String FIND_KEY = """
            SELECT request_hash, status, payment_id, response::text AS response
              FROM idempotency_record WHERE experiment_id = ? AND idempotency_key = ?
            """;
    // @snippet:end

    public DuplicatePaymentExperiment(JdbcTemplate jdbc, TransactionCoordinator coordinator, ConsistencyValidator validator) {
        super(jdbc, coordinator, validator);
    }

    @Override
    public ScenarioDescriptor descriptor() {
        return new ScenarioDescriptor(ID, "Duplicate payment (client retry)", Category.DISTRIBUTED,
                "A client retries a payment after a timeout while the first request is still being processed.",
                "Um cliente envia uma solicitação de pagamento; a resposta sofre timeout e o cliente realiza uma nova tentativa.",
                "payments per idempotency key ≤ 1",
                List.of(NONE, UNIQUE_CONSTRAINT, IDEMPOTENCY_KEY), NONE, false, false,
                List.of("threads", "operationValue", "delayBeforeCommitMs", "lockTimeoutMs"),
                Map.of("threads", "Requests (1 original + retries)", "operationValue", "Payment amount (BRL)",
                        "delayBeforeCommitMs", "Server processing time before commit (ms)"),
                List.of(),
                ConfigBuilder.defaults().operationValue("150.00").initialValue("0").transactions(2).build());
    }

    @Override
    public ScenarioReport execute(ExperimentConfiguration cfg, ExperimentContext ctx) {
        String key = "IDEMP-" + ctx.id();
        BigDecimal amount = cfg.operationValue();
        CountDownLatch originalInFlight = new CountDownLatch(1);
        ctx.event(EventType.DATA_PREPARED, "Client prepared payment of " + amount + " with Idempotency-Key " + key,
                m("idempotencyKey", key, "amount", amount));
        List<WorkerSpec> workers = new ArrayList<>();
        workers.add(WorkerSpec.local("client-request-1", 0, rt -> List.of(original(rt, key, amount, originalInFlight))));
        for (int i = 1; i < Math.max(2, cfg.threads()); i++) {
            int attempt = i + 1;
            workers.add(WorkerSpec.local("client-retry-" + attempt, i,
                    rt -> List.of(retry(rt, key, amount, attempt, originalInFlight))));
        }
        List<TransactionOutcome> outcomes = ctx.runWorkers(workers);
        return report(ctx, key, outcomes);
    }

    // @snippet:start duplicate-payment * java "Original request: the server is still processing when the client times out"
    TransactionOutcome original(WorkerRuntime rt, String key, BigDecimal amount, CountDownLatch inFlight) {
        return coordinator.execute(rt, TxSpec.from(rt.config(), 1, 0, "payment"), tx -> {
            String resource = "payment:" + key;
            if (rt.strategy() == IDEMPOTENCY_KEY) {
                tx.write(resource, "claim idempotency key", () -> tx.jdbc().queryForList(CLAIM_KEY, Long.class,
                        rt.experimentId(), key, hash(key, amount)), ids -> m("claimed", !ids.isEmpty()));
            }
            long paymentId = insertPayment(tx, key, amount);
            inFlight.countDown(); // server still processing… the client's HTTP timeout fires and it retries
            tx.sync("retry-in-flight"); // the retry is now running (or blocked behind this transaction)
            if (rt.strategy() == IDEMPOTENCY_KEY) {
                tx.jdbc().update(COMPLETE_KEY, paymentId, "{\"paymentId\":" + paymentId + ",\"status\":\"CAPTURED\"}",
                        rt.experimentId(), key);
            }
            tx.detail("paymentId", paymentId).detail("response", "201 Created (lost by the client: timeout)");
        });
    }
    // @snippet:end

    // @snippet:start duplicate-payment NONE,UNIQUE_CONSTRAINT,IDEMPOTENCY_KEY java "Client retry with the same request"
    TransactionOutcome retry(WorkerRuntime rt, String key, BigDecimal amount, int attempt, CountDownLatch inFlight) throws InterruptedException {
        if (!inFlight.await(rt.config().transactionTimeoutMs(), TimeUnit.MILLISECONDS)) {
            rt.throwIfCancelled();
        }
        rt.emit(null, null, EventType.RETRY_SCHEDULED, EventSource.INSTRUMENTED, "payment:" + key,
                "Client timed out waiting for the response; retry #" + (attempt - 1) + " with the same Idempotency-Key",
                m("idempotencyKey", key));
        return coordinator.execute(rt, TxSpec.from(rt.config(), 1, 0, "payment-retry").withRetries(0), tx -> {
            String resource = "payment:" + key;
            if (rt.strategy() != IDEMPOTENCY_KEY) {
                long paymentId = insertPayment(tx, key, amount); // NONE: duplicate; UNIQUE: waits, then 23505
                tx.detail("paymentId", paymentId).detail("response", "201 Created (DUPLICATE CHARGE)");
                return;
            }
            List<Long> claimed = tx.write(resource, "INSERT … ON CONFLICT DO NOTHING (claim key)",
                    () -> tx.jdbc().queryForList(CLAIM_KEY, Long.class, rt.experimentId(), key, hash(key, amount)),
                    ids -> m("claimed", !ids.isEmpty()));
            if (!claimed.isEmpty()) {
                long paymentId = insertPayment(tx, key, amount);
                tx.detail("paymentId", paymentId);
                return;
            }
            Map<String, Object> record = tx.read(resource, "load idempotency record",
                    () -> tx.jdbc().queryForMap(FIND_KEY, rt.experimentId(), key),
                    r -> m("status", r.get("status"), "paymentId", r.get("payment_id")));
            if (!hash(key, amount).equals(record.get("request_hash"))) {
                throw new BusinessRuleRejection("IDEMPOTENCY_KEY_REUSED", "Idempotency key reused with a different payload (422)");
            }
            tx.validate(resource, "original request completed", "COMPLETED".equals(record.get("status")),
                    "REQUEST_IN_PROGRESS", "Original request still in progress (409) — retry later");
            tx.detail("paymentId", record.get("payment_id")).detail("replayed", true)
                    .detail("response", "200 OK — original response replayed: " + record.get("response"));
        });
    }
    // @snippet:end

    private long insertPayment(TxScope tx, String key, BigDecimal amount) {
        String sql = tx.runtime().strategy() == NONE ? INSERT_UNSAFE : INSERT_PAYMENT;
        return tx.write("payment:" + key, "INSERT payment " + amount,
                () -> tx.jdbc().queryForObject(sql, Long.class, tx.runtime().experimentId(), key, "customer-42", amount,
                        tx.txId(), tx.workerId()), id -> m("paymentId", id));
    }

    static String hash(String key, BigDecimal amount) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((key + "|customer-42|" + amount.toPlainString())
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ScenarioReport report(ExperimentContext ctx, String key, List<TransactionOutcome> outcomes) {
        long payments = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM payment WHERE experiment_id = ? AND idempotency_key = ?)
                     + (SELECT count(*) FROM payment_unsafe WHERE experiment_id = ? AND idempotency_key = ?)
                """, Long.class, ctx.id(), key, ctx.id(), key);
        BigDecimal charged = money(jdbc.queryForObject("""
                SELECT coalesce((SELECT sum(amount) FROM payment WHERE experiment_id = ? AND idempotency_key = ?), 0)
                     + coalesce((SELECT sum(amount) FROM payment_unsafe WHERE experiment_id = ? AND idempotency_key = ?), 0)
                """, BigDecimal.class, ctx.id(), key, ctx.id(), key));
        long replayed = outcomes.stream().filter(o -> Boolean.TRUE.equals(o.detail("replayed"))).count();
        BigDecimal amount = ctx.config().operationValue();
        List<Violation> violations = validator.validate(List.of(
                Invariant.rule("payments per idempotency key ≤ 1")
                        .description("A retried request must never charge the customer again.")
                        .atMost(1, payments).severity(Severity.CRITICAL)
                        .cause("The retry was processed as a brand-new payment: the server had no way to recognise it.")
                        .recommend(IDEMPOTENCY_KEY, UNIQUE_CONSTRAINT).transactions(committedIds(outcomes)).build()));
        var rows = List.of(
                ConsistencyValidator.row("payment:" + key, "Pagamento", "payments captured", 0, payments, "≤ 1", payments <= 1),
                ConsistencyValidator.row("payment:" + key, "Pagamento", "amount charged", BigDecimal.ZERO, charged, amount),
                ConsistencyValidator.row("payment:" + key, "Pagamento", "responses replayed", 0, replayed, null, true));
        String summary = violations.isEmpty()
                ? "Consistent: one payment captured (" + charged + ")" + (replayed > 0 ? "; the retry received the original response." : ".")
                : "DUPLICATE CHARGE: " + payments + " payments captured for the same request (" + charged + " instead of " + amount + ").";
        return new ScenarioReport(summary,
                List.of(new ResourceState("payment:" + key, "Pagamento", "PAYMENT", m("payments", 0, "charged", BigDecimal.ZERO))),
                List.of(new ResourceState("payment:" + key, "Pagamento", "PAYMENT", m("payments", payments, "charged", charged,
                        "replayedResponses", replayed))),
                rows, violations, List.of());
    }
}

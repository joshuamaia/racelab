package dev.racelab.scenario.deadlock;

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
import dev.racelab.domain.model.Severity;
import dev.racelab.domain.model.StateRow;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.TxStatus;
import dev.racelab.domain.model.Violation;
import dev.racelab.scenario.support.ConfigBuilder;
import dev.racelab.scenario.support.ScenarioSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.racelab.domain.model.StrategyType.LOCK_ORDERING;
import static dev.racelab.domain.model.StrategyType.NONE;

/**
 * Scenario 5 — deadlock. Transaction A locks account 1 then account 2; transaction B locks account 2 then
 * account 1. PostgreSQL's deadlock detector aborts one of them with SQLSTATE 40P01.
 */
@Component
public class DeadlockExperiment extends ScenarioSupport {

    public static final String ID = "deadlock";

    static final String SEED = """
            INSERT INTO account (experiment_id, code, owner, balance)
            SELECT ?, code, owner, ? FROM account WHERE experiment_id = 'TEMPLATE' AND code = ?
            RETURNING id
            """;

    // @snippet:start deadlock NONE,LOCK_ORDERING sql "Row locks and transfer"
    static final String LOCK_ACCOUNT = "SELECT balance FROM account WHERE id = ? FOR UPDATE";
    static final String DEBIT = "UPDATE account SET balance = balance - ?, version = version + 1, update_count = update_count + 1 WHERE id = ?";
    static final String CREDIT = "UPDATE account SET balance = balance + ?, version = version + 1, update_count = update_count + 1 WHERE id = ?";
    // @snippet:end

    static final String MOVEMENT = """
            INSERT INTO account_movement (experiment_id, account_id, type, amount, transaction_id, worker_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    public DeadlockExperiment(JdbcTemplate jdbc, TransactionCoordinator coordinator, ConsistencyValidator validator) {
        super(jdbc, coordinator, validator);
    }

    @Override
    public ScenarioDescriptor descriptor() {
        return new ScenarioDescriptor(ID, "Opposite-order transfers (deadlock)", Category.CONCURRENCY,
                "Two transfers lock the same two accounts in opposite order.",
                "Transação A bloqueia a conta 1 e tenta bloquear a conta 2; a transação B bloqueia a conta 2 e tenta a conta 1.",
                "sum of balances is conserved ∧ every committed transfer is applied exactly once",
                List.of(NONE, LOCK_ORDERING), NONE, true, false,
                List.of("threads", "transactions", "initialValue", "operationValue", "retries", "backoffMs",
                        "delayAfterReadMs", "lockTimeoutMs", "executionMode"),
                Map.of("initialValue", "Initial balance of each account (BRL)", "operationValue", "Transfer amount (BRL)",
                        "retries", "Retries after deadlock"),
                List.of(),
                ConfigBuilder.defaults().initialValue("1000.00").operationValue("100.00").retries(1).backoffMs(50).build());
    }

    @Override
    public ScenarioReport execute(ExperimentConfiguration cfg, ExperimentContext ctx) {
        long a = ctx.setup(s -> seed(ctx.id(), "ACC-A", cfg.initialValue()));
        long b = ctx.setup(s -> seed(ctx.id(), "ACC-B", cfg.initialValue()));
        ctx.event(EventType.DATA_PREPARED, "Accounts ACC-A (id " + a + ") and ACC-B (id " + b + ") seeded with "
                + cfg.initialValue(), m("accountA", a, "accountB", b));
        List<WorkerSpec> workers = new ArrayList<>();
        for (int i = 0; i < cfg.threads(); i++) {
            boolean forward = i % 2 == 0;
            long from = forward ? a : b;
            long to = forward ? b : a;
            workers.add(WorkerSpec.local(i, rt -> perOperation(rt, (op, round) -> transfer(rt, from, to, op, round))));
        }
        List<TransactionOutcome> outcomes = ctx.runWorkers(workers);
        return report(ctx, a, b, cfg.initialValue(), outcomes);
    }

    private long seed(String experimentId, String code, BigDecimal balance) {
        return jdbc.queryForObject(SEED, Long.class, experimentId, balance, code);
    }

    // @snippet:start deadlock NONE,LOCK_ORDERING java "Transfer: lock both accounts, then move the money"
    TransactionOutcome transfer(WorkerRuntime rt, long from, long to, int op, int round) {
        BigDecimal amount = rt.config().operationValue();
        boolean ordered = rt.strategy() == LOCK_ORDERING;
        long first = ordered ? Math.min(from, to) : from;   // LOCK_ORDERING: always lowest id first
        long second = ordered ? Math.max(from, to) : to;    // NONE: source first → opposite orders → cycle
        return coordinator.execute(rt, TxSpec.from(rt.config(), op, round, "transfer"), tx -> {
            tx.detail("from", from).detail("to", to).detail("amount", amount);
            BigDecimal firstBalance = lockAccount(tx, first);
            tx.sync("after-first-lock"); // both transactions now hold one lock each
            BigDecimal secondBalance = lockAccount(tx, second);
            BigDecimal sourceBalance = first == from ? firstBalance : secondBalance;
            tx.validate("account:" + from, "balance ≥ " + amount, sourceBalance.compareTo(amount) >= 0,
                    "INSUFFICIENT_BALANCE", "Insufficient balance");
            tx.write("account:" + from + "→" + to, "debit " + from + ", credit " + to, () -> {
                tx.jdbc().update(DEBIT, amount, from);
                tx.jdbc().update(CREDIT, amount, to);
                tx.jdbc().update(MOVEMENT, rt.experimentId(), from, "TRANSFER_OUT", amount, tx.txId(), tx.workerId());
                return tx.jdbc().update(MOVEMENT, rt.experimentId(), to, "TRANSFER_IN", amount, tx.txId(), tx.workerId());
            }, n -> m("amount", amount));
        });
    }

    private BigDecimal lockAccount(TxScope tx, long accountId) {
        return tx.lock(BlockRegistry.Kind.DATABASE, "account:" + accountId, "SELECT … FOR UPDATE account " + accountId,
                () -> tx.jdbc().queryForObject(LOCK_ACCOUNT, BigDecimal.class, accountId));
    }
    // @snippet:end

    private ScenarioReport report(ExperimentContext ctx, long a, long b, BigDecimal initial, List<TransactionOutcome> outcomes) {
        BigDecimal balanceA = jdbc.queryForObject("SELECT balance FROM account WHERE id = ?", BigDecimal.class, a);
        BigDecimal balanceB = jdbc.queryForObject("SELECT balance FROM account WHERE id = ?", BigDecimal.class, b);
        BigDecimal moved = BigDecimal.ZERO;
        for (TransactionOutcome o : outcomes) {
            if (o.status() == TxStatus.COMMITTED) {
                BigDecimal amount = money(o.detail("amount"));
                moved = ((Number) o.detail("from")).longValue() == a ? moved.subtract(amount) : moved.add(amount);
            }
        }
        BigDecimal expectedA = initial.add(moved);
        BigDecimal expectedB = initial.subtract(moved);
        BigDecimal total = balanceA.add(balanceB);
        BigDecimal expectedTotal = initial.multiply(BigDecimal.TWO);
        List<Violation> violations = validator.validate(List.of(
                Invariant.rule("sum of balances is conserved").equalTo(expectedTotal, total)
                        .severity(Severity.CRITICAL).cause("A transfer was partially applied.")
                        .recommend(LOCK_ORDERING).build(),
                Invariant.rule("account A reflects every committed transfer").equalTo(expectedA, balanceA)
                        .severity(Severity.HIGH).cause("Lost or duplicated transfer.").recommend(LOCK_ORDERING).build()));
        long deadlocks = outcomes.stream().mapToLong(o -> o.transactionIds().size()).sum()
                - outcomes.size();
        List<StateRow> rows = List.of(
                ConsistencyValidator.row("account:ACC-A", "Conta A", "balance", initial, balanceA, expectedA),
                ConsistencyValidator.row("account:ACC-B", "Conta B", "balance", initial, balanceB, expectedB),
                ConsistencyValidator.row("accounts", "Contas A+B", "total", expectedTotal, total, expectedTotal),
                ConsistencyValidator.row("accounts", "Transfers", "committed", 0, count(outcomes, TxStatus.COMMITTED), null, true),
                ConsistencyValidator.row("accounts", "Transfers", "aborted attempts (retried)", 0, deadlocks, null, true));
        String summary = (violations.isEmpty() ? "Consistent: " : "INCONSISTENT: ")
                + count(outcomes, TxStatus.COMMITTED) + " transfer(s) committed, "
                + count(outcomes, TxStatus.ROLLED_BACK) + " rolled back; " + ctx.deadlocks().size()
                + " deadlock(s) detected by PostgreSQL. Total balance " + total + ".";
        return new ScenarioReport(summary,
                List.of(new ResourceState("account:ACC-A", "Conta A", "ACCOUNT", m("balance", initial)),
                        new ResourceState("account:ACC-B", "Conta B", "ACCOUNT", m("balance", initial))),
                List.of(new ResourceState("account:ACC-A", "Conta A", "ACCOUNT", m("balance", balanceA)),
                        new ResourceState("account:ACC-B", "Conta B", "ACCOUNT", m("balance", balanceB))),
                rows, violations, List.of());
    }
}

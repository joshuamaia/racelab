package dev.racelab.scenario.bank;

import dev.racelab.application.engine.BlockRegistry;
import dev.racelab.application.engine.ConsistencyValidator;
import dev.racelab.application.engine.ExperimentContext;
import dev.racelab.application.engine.Invariant;
import dev.racelab.application.engine.ScenarioReport;
import dev.racelab.application.engine.TransactionCoordinator;
import dev.racelab.application.engine.TxGuard;
import dev.racelab.application.engine.TxScope;
import dev.racelab.application.engine.TxSpec;
import dev.racelab.application.engine.WorkerRuntime;
import dev.racelab.application.engine.WorkerSpec;
import dev.racelab.application.engine.WorkerTask;
import dev.racelab.application.port.DistributedLockService;
import dev.racelab.application.port.RemoteWorkerGateway.RemoteWorkerRequest;
import dev.racelab.domain.model.Category;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.ResourceState;
import dev.racelab.domain.model.ScenarioDescriptor;
import dev.racelab.domain.model.ScenarioDescriptor.TemplateOption;
import dev.racelab.domain.model.Severity;
import dev.racelab.domain.model.StateRow;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.model.Topology;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.TxStatus;
import dev.racelab.domain.model.Violation;
import dev.racelab.scenario.support.ConfigBuilder;
import dev.racelab.scenario.support.LockGuards;
import dev.racelab.scenario.support.ScenarioSupport;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.racelab.domain.model.StrategyType.ADVISORY_LOCK;
import static dev.racelab.domain.model.StrategyType.ATOMIC_UPDATE;
import static dev.racelab.domain.model.StrategyType.JAVA_SYNCHRONIZED;
import static dev.racelab.domain.model.StrategyType.NONE;
import static dev.racelab.domain.model.StrategyType.OPTIMISTIC_LOCKING;
import static dev.racelab.domain.model.StrategyType.PESSIMISTIC_LOCKING;
import static dev.racelab.domain.model.StrategyType.REDIS_LOCK;
import static dev.racelab.domain.model.StrategyType.REENTRANT_LOCK;
import static dev.racelab.domain.model.StrategyType.SERIALIZABLE;

/**
 * Scenario 1 — bank account. N transactions withdraw concurrently from the same account.
 * Without control, every transaction reads the same balance and overwrites the others' writes (lost update).
 */
@Component
public class BankWithdrawalExperiment extends ScenarioSupport {

    public static final String ID = "bank-withdrawal";

    // @snippet:start bank-withdrawal * sql "Isolated copy of the template account for this experiment"
    static final String SEED_ACCOUNT = """
            INSERT INTO account (experiment_id, code, owner, balance)
            SELECT ?, code, owner, ? FROM account WHERE experiment_id = 'TEMPLATE' AND code = ?
            RETURNING id
            """;
    // @snippet:end

    // @snippet:start bank-withdrawal NONE,SERIALIZABLE,JAVA_SYNCHRONIZED,REENTRANT_LOCK,ADVISORY_LOCK,REDIS_LOCK sql "Plain read and absolute write"
    static final String SELECT_ACCOUNT = """
            SELECT id, code, owner, balance, version, update_count FROM account WHERE id = ?
            """;
    static final String UPDATE_ABSOLUTE = """
            UPDATE account
               SET balance = ?, version = version + 1, update_count = update_count + 1
             WHERE id = ?
            """;
    // @snippet:end

    // @snippet:start bank-withdrawal ATOMIC_UPDATE sql "Atomic conditional update"
    static final String WITHDRAW_ATOMIC = """
            UPDATE account
               SET balance = balance - ?, version = version + 1, update_count = update_count + 1
             WHERE id = ?
               AND balance >= ?
            """;
    // @snippet:end

    // @snippet:start bank-withdrawal ADVISORY_LOCK sql "Transaction-scoped advisory lock"
    static final String ADVISORY = "SELECT pg_advisory_xact_lock(?)";
    // @snippet:end

    // @snippet:start bank-withdrawal * sql "Movement recorded in the same transaction"
    static final String INSERT_MOVEMENT = """
            INSERT INTO account_movement
                (experiment_id, account_id, type, amount, balance_before, balance_after, transaction_id, worker_id)
            VALUES (?, ?, 'WITHDRAWAL', ?, ?, ?, ?, ?)
            """;
    // @snippet:end

    static final RowMapper<AccountRow> ACCOUNT_MAPPER = (rs, i) -> new AccountRow(rs.getLong("id"),
            rs.getString("code"), rs.getString("owner"), rs.getBigDecimal("balance"), rs.getLong("version"),
            rs.getInt("update_count"));

    private final AccountJpaRepository accounts;
    private final EntityManager entityManager;
    private final DistributedLockService distributedLocks;

    public BankWithdrawalExperiment(JdbcTemplate jdbc, TransactionCoordinator coordinator, ConsistencyValidator validator,
                                    AccountJpaRepository accounts, EntityManager entityManager,
                                    DistributedLockService distributedLocks) {
        super(jdbc, coordinator, validator);
        this.accounts = accounts;
        this.entityManager = entityManager;
        this.distributedLocks = distributedLocks;
    }

    public record AccountRow(long id, String code, String owner, BigDecimal balance, long version, int updateCount) {
    }

    @Override
    public ScenarioDescriptor descriptor() {
        return new ScenarioDescriptor(ID, "Concurrent withdrawals", Category.CONCURRENCY,
                "Several transactions withdraw from the same account at the same time.",
                "Conta bancária: saldo inicial de R$ 1.000,00 e dois saques simultâneos de R$ 800,00.",
                "initial balance − confirmed withdrawals = final balance ∧ confirmed withdrawals ≤ initial balance",
                List.of(NONE, OPTIMISTIC_LOCKING, PESSIMISTIC_LOCKING, ATOMIC_UPDATE, SERIALIZABLE, JAVA_SYNCHRONIZED,
                        REENTRANT_LOCK, ADVISORY_LOCK, REDIS_LOCK),
                NONE, true, true,
                List.of("threads", "transactions", "initialValue", "operationValue", "resourceCode", "isolationLevel",
                        "delayAfterReadMs", "delayBeforeCommitMs", "retries", "backoffMs", "lockTimeoutMs", "lockTtlMs",
                        "topology", "executionMode", "coordinated"),
                Map.of("initialValue", "Initial balance (BRL)", "operationValue", "Withdrawal amount (BRL)",
                        "resourceCode", "Account template", "threads", "Concurrent workers",
                        "transactions", "Withdrawals (total)"),
                List.of(new TemplateOption("ACC-A", "Conta A — R$ 1.000,00"),
                        new TemplateOption("ACC-B", "Conta B — R$ 2.000,00"),
                        new TemplateOption("ACC-C", "Conta C — R$ 5.000,00")),
                ConfigBuilder.defaults().resourceCode("ACC-A").build());
    }

    @Override
    public ScenarioReport execute(ExperimentConfiguration cfg, ExperimentContext ctx) {
        String code = cfg.resourceCode() == null ? "ACC-A" : cfg.resourceCode();
        long accountId = ctx.setup(s -> seed(ctx.id(), code, cfg.initialValue()));
        AccountRow initial = jdbc.queryForObject(SELECT_ACCOUNT, ACCOUNT_MAPPER, accountId);
        ctx.event(EventType.DATA_PREPARED, "Account " + code + " seeded with balance " + initial.balance()
                + " in namespace " + ctx.id(), m("accountId", accountId, "balance", initial.balance()));

        List<WorkerSpec> workers = new ArrayList<>();
        for (int i = 0; i < cfg.threads(); i++) {
            int index = i;
            boolean remote = cfg.topology() == Topology.DISTRIBUTED && i % 2 == 1;
            WorkerTask task = rt -> perOperation(rt, (op, round) -> withdraw(rt, accountId, code, op, round));
            workers.add(new WorkerSpec("worker-" + (i + 1), index, task, remote, operationsOf(cfg, index),
                    m("accountId", accountId, "code", code)));
        }
        List<TransactionOutcome> outcomes = ctx.runWorkers(workers);
        return report(cfg, ctx, initial, outcomes);
    }

    @Override
    public Optional<WorkerTask> remoteWorker(RemoteWorkerRequest request) {
        long accountId = ((Number) request.data().get("accountId")).longValue();
        String code = (String) request.data().get("code");
        return Optional.of(rt -> perOperation(rt, request.operations(),
                (op, round) -> withdraw(rt, accountId, code, op, round)));
    }

    long seed(String experimentId, String code, BigDecimal initial) {
        List<Long> ids = jdbc.queryForList(SEED_ACCOUNT, Long.class, experimentId, initial, code);
        if (!ids.isEmpty()) {
            return ids.getFirst();
        }
        return jdbc.queryForObject("INSERT INTO account (experiment_id, code, owner, balance) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, experimentId, code, "Conta " + code, initial);
    }

    // ------------------------------------------------------------------------------------------ strategies

    TransactionOutcome withdraw(WorkerRuntime rt, long accountId, String code, int op, int round) {
        ExperimentConfiguration cfg = rt.config();
        String resource = "account:" + code;
        StrategyType strategy = rt.strategy();
        TxSpec spec = TxSpec.from(cfg, op, round, "withdraw");
        if (strategy == SERIALIZABLE) {
            spec = spec.withIsolation(dev.racelab.domain.model.IsolationLevel.SERIALIZABLE);
        }
        String lockKey = "account:" + accountId;
        TxGuard guard = switch (strategy) {
            case JAVA_SYNCHRONIZED -> LockGuards.synchronizedGuard(rt, lockKey, resource);
            case REENTRANT_LOCK -> LockGuards.reentrantGuard(rt, lockKey, resource);
            case REDIS_LOCK -> LockGuards.redisGuard(rt, distributedLocks,
                    "racelab:lock:" + rt.experimentId() + ":" + lockKey, resource);
            default -> TxGuard.NONE;
        };
        BigDecimal amount = cfg.operationValue();
        return coordinator.execute(rt, spec, guard, tx -> {
            tx.detail("amount", amount);
            switch (strategy) {
                case OPTIMISTIC_LOCKING -> optimistic(tx, accountId, resource, amount);
                case PESSIMISTIC_LOCKING -> pessimistic(tx, accountId, resource, amount);
                case ATOMIC_UPDATE -> atomic(tx, accountId, resource, amount);
                case ADVISORY_LOCK -> {
                    advisoryLock(tx, accountId, resource);
                    readValidateWrite(tx, accountId, resource, amount);
                }
                default -> readValidateWrite(tx, accountId, resource, amount);
            }
        });
    }

    // @snippet:start bank-withdrawal NONE,SERIALIZABLE,JAVA_SYNCHRONIZED,REENTRANT_LOCK,ADVISORY_LOCK,REDIS_LOCK java "Read → validate → write"
    private void readValidateWrite(TxScope tx, long accountId, String resource, BigDecimal amount) {
        AccountRow account = tx.read(resource, "SELECT balance",
                () -> tx.jdbc().queryForObject(SELECT_ACCOUNT, ACCOUNT_MAPPER, accountId),
                a -> m("balance", a.balance(), "version", a.version()));
        tx.before("balance", account.balance());
        tx.sync("after-read"); // every transaction has read before anyone writes

        tx.validate(resource, "balance ≥ " + amount, account.balance().compareTo(amount) >= 0,
                "INSUFFICIENT_BALANCE", "Insufficient balance: " + account.balance() + " < " + amount);

        BigDecimal newBalance = account.balance().subtract(amount); // computed from a possibly stale read
        tx.write(resource, "UPDATE account SET balance = " + newBalance,
                () -> tx.jdbc().update(UPDATE_ABSOLUTE, newBalance, accountId),
                rows -> m("rowsAffected", rows, "balance", newBalance));
        recordMovement(tx, accountId, amount, account.balance(), newBalance);
    }
    // @snippet:end

    // @snippet:start bank-withdrawal OPTIMISTIC_LOCKING java "Optimistic locking with JPA @Version"
    private void optimistic(TxScope tx, long accountId, String resource, BigDecimal amount) {
        AccountEntity account = tx.read(resource, "find account (JPA)",
                () -> accounts.findById(accountId).orElseThrow(),
                a -> m("balance", a.getBalance(), "version", a.getVersion()));
        BigDecimal before = account.getBalance();
        long readVersion = account.getVersion();
        tx.before("balance", before).before("version", readVersion);
        tx.sync("after-read");

        tx.validate(resource, "balance ≥ " + amount, before.compareTo(amount) >= 0,
                "INSUFFICIENT_BALANCE", "Insufficient balance: " + before + " < " + amount);

        account.withdraw(amount);
        tx.write(resource, "UPDATE … WHERE id = " + accountId + " AND version = " + readVersion,
                () -> {
                    entityManager.flush(); // 0 rows updated ⇒ OptimisticLockException
                    return account.getVersion();
                },
                version -> m("balance", account.getBalance(), "newVersion", version));
        recordMovement(tx, accountId, amount, before, account.getBalance());
    }
    // @snippet:end

    // @snippet:start bank-withdrawal PESSIMISTIC_LOCKING java "Pessimistic locking: lock on read, hold until commit"
    private void pessimistic(TxScope tx, long accountId, String resource, BigDecimal amount) {
        AccountEntity account = tx.read(resource, "SELECT … FOR UPDATE",
                () -> tx.lock(BlockRegistry.Kind.DATABASE, resource, "row lock (PESSIMISTIC_WRITE)",
                        () -> accounts.findByIdForUpdate(accountId).orElseThrow()),
                a -> m("balance", a.getBalance(), "version", a.getVersion(), "locked", true));
        BigDecimal before = account.getBalance();
        tx.before("balance", before);
        tx.sync("after-read"); // the other transactions are parked on the row lock

        tx.validate(resource, "balance ≥ " + amount, before.compareTo(amount) >= 0,
                "INSUFFICIENT_BALANCE", "Insufficient balance: " + before + " < " + amount);

        account.withdraw(amount);
        tx.write(resource, "UPDATE account SET balance = " + account.getBalance(),
                () -> {
                    entityManager.flush();
                    return account.getBalance();
                },
                balance -> m("balance", balance));
        recordMovement(tx, accountId, amount, before, account.getBalance());
    }
    // @snippet:end

    // @snippet:start bank-withdrawal ATOMIC_UPDATE java "Atomic conditional update + row count check"
    private void atomic(TxScope tx, long accountId, String resource, BigDecimal amount) {
        AccountRow seen = tx.read(resource, "SELECT balance (informative only)",
                () -> tx.jdbc().queryForObject(SELECT_ACCOUNT, ACCOUNT_MAPPER, accountId),
                a -> m("balance", a.balance()));
        tx.sync("after-read");

        int updated = tx.write(resource, "UPDATE … SET balance = balance - " + amount + " WHERE balance >= " + amount,
                () -> tx.jdbc().update(WITHDRAW_ATOMIC, amount, accountId, amount),
                rows -> m("rowsAffected", rows));
        // the database re-evaluates "balance >= amount" on the latest committed row version
        tx.validate(resource, "rows affected = 1", updated == 1,
                "INSUFFICIENT_BALANCE", "Conditional update affected 0 rows (insufficient balance at write time)");

        BigDecimal after = tx.jdbc().queryForObject("SELECT balance FROM account WHERE id = ?", BigDecimal.class, accountId);
        tx.before("balanceSeen", seen.balance());
        recordMovement(tx, accountId, amount, after.add(amount), after);
    }
    // @snippet:end

    // @snippet:start bank-withdrawal ADVISORY_LOCK java "pg_advisory_xact_lock before the read"
    private void advisoryLock(TxScope tx, long accountId, String resource) {
        tx.lock(BlockRegistry.Kind.DATABASE, "advisory:" + accountId, "pg_advisory_xact_lock(" + accountId + ")",
                () -> tx.jdbc().queryForList(ADVISORY, accountId));
        // released automatically by PostgreSQL at COMMIT/ROLLBACK
    }
    // @snippet:end

    private void recordMovement(TxScope tx, long accountId, BigDecimal amount, BigDecimal before, BigDecimal after) {
        tx.jdbc().update(INSERT_MOVEMENT, tx.runtime().experimentId(), accountId, amount, before, after, tx.txId(),
                tx.workerId());
        tx.after("balance", after);
    }

    // ------------------------------------------------------------------------------------------ validation

    private ScenarioReport report(ExperimentConfiguration cfg, ExperimentContext ctx, AccountRow initial,
                                  List<TransactionOutcome> outcomes) {
        AccountRow fin = jdbc.queryForObject(SELECT_ACCOUNT, ACCOUNT_MAPPER, initial.id());
        Map<String, Object> mv = jdbc.queryForMap("""
                SELECT count(*) AS movements, coalesce(sum(amount), 0) AS withdrawn
                FROM account_movement WHERE experiment_id = ? AND account_id = ?
                """, ctx.id(), initial.id());
        long movements = asLong(mv.get("movements"));
        BigDecimal withdrawn = money(mv.get("withdrawn"));
        BigDecimal expectedBalance = initial.balance().subtract(withdrawn);
        List<String> committed = committedIds(outcomes);

        List<Violation> violations = validator.validate(List.of(
                Invariant.rule("initial balance − confirmed withdrawals = final balance")
                        .description("Every confirmed withdrawal must be reflected in the balance.")
                        .equalTo(expectedBalance, fin.balance())
                        .severity(Severity.CRITICAL)
                        .cause("Lost update: concurrent transactions computed the new balance from the same stale read "
                                + "and the last commit overwrote the others.")
                        .recommend(ATOMIC_UPDATE, OPTIMISTIC_LOCKING, PESSIMISTIC_LOCKING, SERIALIZABLE)
                        .transactions(committed).build(),
                Invariant.rule("confirmed withdrawals ≤ initial balance")
                        .description("The bank must never pay out more money than the account had.")
                        .atMost(initial.balance(), withdrawn)
                        .severity(Severity.CRITICAL)
                        .cause("Check-then-act race: every transaction validated the balance before the others wrote.")
                        .recommend(ATOMIC_UPDATE, PESSIMISTIC_LOCKING, OPTIMISTIC_LOCKING)
                        .transactions(committed).build(),
                Invariant.rule("final balance ≥ 0")
                        .atLeast(BigDecimal.ZERO, fin.balance())
                        .severity(Severity.HIGH)
                        .cause("Withdrawals applied without a guard on the balance.")
                        .recommend(ATOMIC_UPDATE).transactions(committed).build()));

        String res = "account:" + initial.code();
        List<StateRow> rows = List.of(
                ConsistencyValidator.row(res, initial.owner(), "balance", initial.balance(), fin.balance(), expectedBalance),
                ConsistencyValidator.row(res, initial.owner(), "confirmed withdrawals (count)", 0, movements,
                        String.valueOf(count(outcomes, TxStatus.COMMITTED)), movements == count(outcomes, TxStatus.COMMITTED)),
                ConsistencyValidator.row(res, initial.owner(), "total withdrawn", BigDecimal.ZERO, withdrawn,
                        "≤ " + initial.balance(), withdrawn.compareTo(initial.balance()) <= 0),
                ConsistencyValidator.row(res, initial.owner(), "version", initial.version(), fin.version(),
                        null, true),
                ConsistencyValidator.row(res, initial.owner(), "updates applied", initial.updateCount(), fin.updateCount(),
                        null, true));

        String summary;
        if (violations.isEmpty()) {
            summary = "Consistent: " + movements + " withdrawal(s) confirmed (" + withdrawn + "), "
                    + (outcomes.size() - movements) + " rejected/rolled back; final balance " + fin.balance() + ".";
        } else {
            summary = "INCONSISTENT: " + movements + " withdrawals confirmed totalling " + withdrawn
                    + " but the balance only went from " + initial.balance() + " to " + fin.balance()
                    + " (expected " + expectedBalance + ").";
        }
        return new ScenarioReport(summary,
                List.of(state(initial)), List.of(state(fin)), rows, violations, List.of());
    }

    private static ResourceState state(AccountRow a) {
        return new ResourceState("account:" + a.code(), a.owner(), "ACCOUNT",
                m("balance", a.balance(), "version", a.version(), "updateCount", a.updateCount()));
    }
}

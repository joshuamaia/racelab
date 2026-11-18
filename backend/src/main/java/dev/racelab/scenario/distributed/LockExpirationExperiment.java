package dev.racelab.scenario.distributed;

import dev.racelab.application.engine.BlockRegistry;
import dev.racelab.application.engine.ConsistencyValidator;
import dev.racelab.application.engine.ExperimentContext;
import dev.racelab.application.engine.Invariant;
import dev.racelab.application.engine.ScenarioExceptions.LockNotAcquiredException;
import dev.racelab.application.engine.ScenarioReport;
import dev.racelab.application.engine.TransactionCoordinator;
import dev.racelab.application.engine.TxSpec;
import dev.racelab.application.engine.WorkerRuntime;
import dev.racelab.application.engine.WorkerSpec;
import dev.racelab.application.port.DistributedLockService;
import dev.racelab.application.port.DistributedLockService.Lease;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.racelab.domain.model.StrategyType.FENCING_TOKEN;
import static dev.racelab.domain.model.StrategyType.REDIS_LOCK;

/**
 * Distributed lock expiration. Client 1 holds a Redis lease and stalls (GC pause, slow I/O) longer than the
 * TTL; the lease really expires in Redis, client 2 acquires it, and both believe they own the account.
 * Fencing tokens let the database reject the stale owner.
 */
@Component
public class LockExpirationExperiment extends ScenarioSupport {

    public static final String ID = "lock-expiration";

    static final String READ = "SELECT balance, last_fencing_token FROM account WHERE id = ?";

    // @snippet:start lock-expiration REDIS_LOCK sql "Write protected only by the Redis lease"
    static final String WRITE_PLAIN = """
            UPDATE account SET balance = ?, version = version + 1, update_count = update_count + 1 WHERE id = ?
            """;
    // @snippet:end

    // @snippet:start lock-expiration FENCING_TOKEN sql "Fenced write: the database rejects older tokens"
    static final String WRITE_FENCED = """
            UPDATE account
               SET balance = ?, last_fencing_token = ?, version = version + 1, update_count = update_count + 1
             WHERE id = ? AND last_fencing_token < ?
            """;
    // @snippet:end

    static final String MOVEMENT = """
            INSERT INTO account_movement (experiment_id, account_id, type, amount, transaction_id, worker_id, fencing_token)
            VALUES (?, ?, 'WITHDRAWAL', ?, ?, ?, ?)
            """;

    private final DistributedLockService locks;

    public LockExpirationExperiment(JdbcTemplate jdbc, TransactionCoordinator coordinator, ConsistencyValidator validator,
                                    DistributedLockService locks) {
        super(jdbc, coordinator, validator);
        this.locks = locks;
    }

    @Override
    public ScenarioDescriptor descriptor() {
        return new ScenarioDescriptor(ID, "Redis lock expiration", Category.DISTRIBUTED,
                "A Redis lease expires while its owner is still working; a second client acquires the lock.",
                "Dois clientes protegem o saque de uma conta com um lock Redis cujo TTL é menor que o processamento.",
                "initial balance − confirmed withdrawals = final balance",
                List.of(REDIS_LOCK, FENCING_TOKEN), REDIS_LOCK, false, false,
                List.of("lockTtlMs", "initialValue", "operationValue", "transactionTimeoutMs"),
                Map.of("lockTtlMs", "Lease TTL (ms)", "initialValue", "Initial balance", "operationValue", "Withdrawal amount"),
                List.of(),
                ConfigBuilder.defaults().threads(2).transactions(2).initialValue("1000.00").operationValue("300.00")
                        .lockTtlMs(800).transactionTimeoutMs(15_000).build());
    }

    @Override
    public ScenarioReport execute(ExperimentConfiguration cfg, ExperimentContext ctx) {
        long id = ctx.setup(s -> jdbc.queryForObject("""
                INSERT INTO account (experiment_id, code, owner, balance)
                SELECT ?, code, owner, ? FROM account WHERE experiment_id = 'TEMPLATE' AND code = 'ACC-A' RETURNING id
                """, Long.class, ctx.id(), cfg.initialValue()));
        String key = "racelab:lock:" + ctx.id() + ":account:" + id;
        ctx.event(EventType.DATA_PREPARED, "Account seeded; both clients will use Redis key " + key
                + " with TTL " + cfg.lockTtlMs() + " ms", m("accountId", id, "lockKey", key));
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch secondCommitted = new CountDownLatch(1);
        List<TransactionOutcome> outcomes = ctx.runWorkers(List.of(
                WorkerSpec.local("client-1", 0, rt -> List.of(slowClient(rt, id, key, firstAcquired, secondCommitted))),
                WorkerSpec.local("client-2", 1, rt -> List.of(fastClient(rt, id, key, firstAcquired, secondCommitted)))));
        return report(ctx, id, cfg.initialValue(), outcomes);
    }

    // @snippet:start lock-expiration REDIS_LOCK,FENCING_TOKEN java "Client 1: acquires the lease, then stalls past the TTL"
    TransactionOutcome slowClient(WorkerRuntime rt, long accountId, String key, CountDownLatch acquired,
                                  CountDownLatch otherCommitted) throws InterruptedException {
        Lease lease = acquire(rt, key);
        acquired.countDown();
        try {
            return coordinator.execute(rt, TxSpec.from(rt.config(), 1, 0, "withdraw").withRetries(0), tx -> {
                BigDecimal balance = tx.read("account:" + accountId, "SELECT balance",
                        () -> tx.jdbc().queryForMap(READ, accountId), r -> m("balance", r.get("balance"))).get("balance") instanceof BigDecimal b ? b : BigDecimal.ZERO;
                tx.emit(EventType.LOCK_WAITING, key, "Stalled (GC pause / slow I/O): lease of " + rt.config().lockTtlMs()
                        + " ms will expire while this client still believes it owns the lock", m("fencingToken", lease.fencingToken()));
                if (!otherCommitted.await(rt.config().transactionTimeoutMs(), TimeUnit.MILLISECONDS)) {
                    rt.throwIfCancelled();
                }
                write(tx, accountId, balance, lease, rt);
            });
        } finally {
            release(rt, lease);
        }
    }
    // @snippet:end

    // @snippet:start lock-expiration REDIS_LOCK,FENCING_TOKEN java "Client 2: gets the lock after the lease expired"
    TransactionOutcome fastClient(WorkerRuntime rt, long accountId, String key, CountDownLatch firstAcquired,
                                  CountDownLatch committed) throws InterruptedException {
        firstAcquired.await(rt.config().transactionTimeoutMs(), TimeUnit.MILLISECONDS);
        Lease lease = acquire(rt, key); // polls until Redis really expires client 1's lease
        try {
            return coordinator.execute(rt, TxSpec.from(rt.config(), 1, 0, "withdraw").withRetries(0), tx -> {
                BigDecimal balance = (BigDecimal) tx.read("account:" + accountId, "SELECT balance",
                        () -> tx.jdbc().queryForMap(READ, accountId), r -> m("balance", r.get("balance"))).get("balance");
                write(tx, accountId, balance, lease, rt);
            });
        } finally {
            committed.countDown();
            release(rt, lease);
        }
    }
    // @snippet:end

    // @snippet:start lock-expiration REDIS_LOCK,FENCING_TOKEN java "Write: plain, or fenced by the token"
    private void write(dev.racelab.application.engine.TxScope tx, long accountId, BigDecimal balance, Lease lease, WorkerRuntime rt) {
        BigDecimal amount = rt.config().operationValue();
        BigDecimal newBalance = balance.subtract(amount);
        tx.before("balance", balance).detail("fencingToken", lease.fencingToken()).detail("amount", amount);
        int rows = tx.write("account:" + accountId, rt.strategy() == FENCING_TOKEN
                        ? "UPDATE … WHERE last_fencing_token < " + lease.fencingToken() : "UPDATE balance = " + newBalance,
                () -> rt.strategy() == FENCING_TOKEN
                        ? tx.jdbc().update(WRITE_FENCED, newBalance, lease.fencingToken(), accountId, lease.fencingToken())
                        : tx.jdbc().update(WRITE_PLAIN, newBalance, accountId),
                n -> m("rowsAffected", n, "fencingToken", lease.fencingToken()));
        if (rows == 0) {
            throw new LockNotAcquiredException("stale fencing token " + lease.fencingToken()
                    + " rejected by the database (a newer owner already wrote)");
        }
        tx.jdbc().update(MOVEMENT, rt.experimentId(), accountId, amount, tx.txId(), tx.workerId(), lease.fencingToken());
        tx.after("balance", newBalance);
    }
    // @snippet:end

    private Lease acquire(WorkerRuntime rt, String key) {
        String owner = rt.workerId() + ":" + UUID.randomUUID();
        Duration ttl = Duration.ofMillis(rt.config().lockTtlMs());
        rt.emit(null, null, EventType.LOCK_REQUESTED, EventSource.INSTRUMENTED, key, "SET " + key + " NX PX " + ttl.toMillis(),
                m("lockKind", "REDIS"));
        long start = System.nanoTime();
        Optional<Lease> lease = locks.tryAcquire(key, owner, ttl);
        if (lease.isEmpty()) {
            rt.emit(null, null, EventType.LOCK_WAITING, EventSource.INSTRUMENTED, key,
                    "Lock owned by " + locks.currentOwner(key).orElse("?") + " — polling Redis until it is released or expires",
                    m("lockKind", "REDIS"));
            rt.block(BlockRegistry.Kind.REDIS, "Redis lease " + key, null);
            long deadline = System.currentTimeMillis() + rt.config().transactionTimeoutMs();
            try {
                while (lease.isEmpty() && System.currentTimeMillis() < deadline) {
                    rt.pause(25);
                    lease = locks.tryAcquire(key, owner, ttl);
                }
            } finally {
                rt.unblock();
            }
        }
        Lease acquired = lease.orElseThrow(() -> new LockNotAcquiredException("Redis lock not acquired"));
        rt.emit(null, null, EventType.LOCK_ACQUIRED, EventSource.INSTRUMENTED, key,
                "Lease acquired — fencing token " + acquired.fencingToken() + " (after " + (System.nanoTime() - start) / 1_000_000 + " ms)",
                m("fencingToken", acquired.fencingToken(), "ttlMs", ttl.toMillis()));
        return acquired;
    }

    private void release(WorkerRuntime rt, Lease lease) {
        boolean released = locks.release(lease);
        rt.emit(null, null, EventType.LOCK_RELEASED, EventSource.INSTRUMENTED, lease.key(), released
                        ? "Lease released by its owner"
                        : "Release refused: the lease had EXPIRED and the key now belongs to another client",
                m("released", released, "fencingToken", lease.fencingToken()));
    }

    private ScenarioReport report(ExperimentContext ctx, long id, BigDecimal initial, List<TransactionOutcome> outcomes) {
        Map<String, Object> fin = jdbc.queryForMap("SELECT balance, last_fencing_token, update_count FROM account WHERE id = ?", id);
        BigDecimal balance = (BigDecimal) fin.get("balance");
        BigDecimal withdrawn = money(jdbc.queryForObject(
                "SELECT coalesce(sum(amount), 0) FROM account_movement WHERE experiment_id = ? AND account_id = ?",
                BigDecimal.class, ctx.id(), id));
        BigDecimal expected = initial.subtract(withdrawn);
        List<Violation> violations = validator.validate(List.of(
                Invariant.rule("initial balance − confirmed withdrawals = final balance")
                        .equalTo(expected, balance).severity(Severity.CRITICAL)
                        .description("Both lock holders wrote the account; the stale owner overwrote the newer write.")
                        .cause("The Redis lease expired while client-1 was paused; client-2 legitimately acquired the lock. "
                                + "A lease alone cannot guarantee mutual exclusion.")
                        .recommend(FENCING_TOKEN).transactions(committedIds(outcomes)).build()));
        var rows = List.of(
                ConsistencyValidator.row("account:ACC-A", "Conta A", "balance", initial, balance, expected),
                ConsistencyValidator.row("account:ACC-A", "Conta A", "confirmed withdrawals", BigDecimal.ZERO, withdrawn, null, true),
                ConsistencyValidator.row("account:ACC-A", "Conta A", "last fencing token", 0, fin.get("last_fencing_token"), null, true));
        String summary = violations.isEmpty()
                ? "Consistent: the stale owner was fenced off; final balance " + balance + "."
                : "TWO OWNERS: both clients wrote under 'the same' lock; withdrawals " + withdrawn + " but balance " + balance
                + " (expected " + expected + ").";
        return new ScenarioReport(summary,
                List.of(new ResourceState("account:ACC-A", "Conta A", "ACCOUNT", m("balance", initial))),
                List.of(new ResourceState("account:ACC-A", "Conta A", "ACCOUNT", m("balance", balance,
                        "lastFencingToken", fin.get("last_fencing_token"), "updates", fin.get("update_count")))),
                rows, violations, List.of());
    }
}

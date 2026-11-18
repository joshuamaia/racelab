package dev.racelab.scenario.isolation;

import dev.racelab.application.engine.ExperimentContext;
import dev.racelab.application.engine.ScenarioExceptions.IntentionalRollback;
import dev.racelab.application.engine.TransactionCoordinator;
import dev.racelab.application.engine.TxSpec;
import dev.racelab.application.engine.WorkerRuntime;
import dev.racelab.application.engine.WorkerSpec;
import dev.racelab.domain.model.EventType;
import dev.racelab.domain.model.IsolationLevel;
import dev.racelab.domain.model.Observation;
import dev.racelab.domain.model.ResourceState;
import dev.racelab.domain.model.StateRow;
import dev.racelab.application.engine.ConsistencyValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Read-phenomena probes. A reader transaction runs at the configured isolation level while a writer
 * (READ COMMITTED) changes the data in a strictly ordered interleaving. The probes report what PostgreSQL
 * really returned — nothing is fabricated: if PostgreSQL prevents a phenomenon, the observation says so.
 */
@Component
public class IsolationProbes {

    // @snippet:start dirty-read,non-repeatable-read,phantom-read,isolation-probe * sql "Probe statements"
    static final String READ_BALANCE = "SELECT balance FROM account WHERE id = ?";
    static final String UPDATE_BALANCE = "UPDATE account SET balance = balance - ?, version = version + 1 WHERE id = ?";
    static final String COUNT_MOVEMENTS = "SELECT count(*) FROM account_movement WHERE experiment_id = ? AND account_id = ? AND amount >= 100";
    static final String INSERT_MOVEMENT = """
            INSERT INTO account_movement (experiment_id, account_id, type, amount, transaction_id, worker_id)
            VALUES (?, ?, 'DEPOSIT', ?, ?, ?)
            """;
    // @snippet:end

    private final JdbcTemplate jdbc;
    private final TransactionCoordinator coordinator;

    public IsolationProbes(JdbcTemplate jdbc, TransactionCoordinator coordinator) {
        this.jdbc = jdbc;
        this.coordinator = coordinator;
    }

    public record ProbeResult(Observation observation, List<StateRow> rows, ResourceState initial, ResourceState fin) {
    }

    long seedAccount(ExperimentContext ctx, String code, BigDecimal balance) {
        return ctx.setup(s -> jdbc.queryForObject(
                "INSERT INTO account (experiment_id, code, owner, balance) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, ctx.id(), code, "Probe " + code, balance));
    }

    // @snippet:start dirty-read,non-repeatable-read,phantom-read,isolation-probe * java "Dirty read probe: read while another transaction holds an uncommitted change"
    public ProbeResult dirtyRead(ExperimentContext ctx, IsolationLevel level) {
        BigDecimal initial = ctx.config().initialValue();
        BigDecimal delta = ctx.config().operationValue();
        long id = seedAccount(ctx, "DIRTY", initial);
        ctx.event(EventType.DATA_PREPARED, "Dirty read probe on account DIRTY (" + initial + ")", Map.of("accountId", id));
        TurnSequencer turns = new TurnSequencer(ctx.config().transactionTimeoutMs());
        AtomicReference<BigDecimal> seen = new AtomicReference<>();
        ctx.runWorkers(List.of(
                WorkerSpec.local("writer", 0, rt -> List.of(coordinator.execute(rt, spec(rt, IsolationLevel.READ_COMMITTED), tx -> {
                    turns.step(1, rt, () -> tx.write("account:DIRTY", "UPDATE balance = balance - " + delta + " (not committed)",
                            () -> tx.jdbc().update(UPDATE_BALANCE, delta, id), n -> Map.of("uncommittedBalance", initial.subtract(delta))));
                    turns.await(3, rt);
                    turns.done(3);
                    throw new IntentionalRollback("Writer rolls back its uncommitted change");
                }))),
                WorkerSpec.local("reader", 1, rt -> List.of(coordinator.execute(rt, spec(rt, level), tx ->
                        turns.step(2, rt, () -> {
                            seen.set(tx.read("account:DIRTY", "SELECT balance while the writer has not committed",
                                    () -> tx.jdbc().queryForObject(READ_BALANCE, BigDecimal.class, id), v -> Map.of("balance", v)));
                            return null;
                        }))))));
        boolean reproduced = seen.get() != null && seen.get().compareTo(initial) != 0;
        Observation o = new Observation("DIRTY_READ", level, level.effectiveInPostgres(), reproduced,
                reproduced ? "Reader saw the uncommitted value " + seen.get()
                        : "Prevented: reader saw only committed data (" + seen.get() + ") while the writer held " + initial.subtract(delta),
                values("uncommittedValue", initial.subtract(delta), "valueSeenByReader", seen.get()),
                level == IsolationLevel.READ_UNCOMMITTED
                        ? "PostgreSQL treats READ UNCOMMITTED as READ COMMITTED: dirty reads are never possible."
                        : "MVCC never exposes row versions of uncommitted transactions in PostgreSQL.");
        return new ProbeResult(o, List.of(ConsistencyValidator.row("account:DIRTY", "Dirty read probe", "value seen by reader",
                initial, seen.get(), initial)), state("DIRTY", initial), state("DIRTY", balance(id)));
    }
    // @snippet:end

    // @snippet:start dirty-read,non-repeatable-read,phantom-read,isolation-probe * java "Non-repeatable read probe: read, concurrent commit, read again"
    public ProbeResult nonRepeatableRead(ExperimentContext ctx, IsolationLevel level) {
        BigDecimal initial = ctx.config().initialValue();
        BigDecimal delta = ctx.config().operationValue();
        long id = seedAccount(ctx, "NRR", initial);
        ctx.event(EventType.DATA_PREPARED, "Non-repeatable read probe on account NRR (" + initial + ")", Map.of("accountId", id));
        TurnSequencer turns = new TurnSequencer(ctx.config().transactionTimeoutMs());
        AtomicReference<BigDecimal> first = new AtomicReference<>();
        AtomicReference<BigDecimal> second = new AtomicReference<>();
        ctx.runWorkers(List.of(
                WorkerSpec.local("reader", 0, rt -> List.of(coordinator.execute(rt, spec(rt, level), tx -> {
                    turns.step(1, rt, () -> {
                        first.set(tx.read("account:NRR", "first SELECT balance",
                                () -> tx.jdbc().queryForObject(READ_BALANCE, BigDecimal.class, id), v -> Map.of("balance", v)));
                        return null;
                    });
                    turns.step(3, rt, () -> {
                        second.set(tx.read("account:NRR", "second SELECT balance (same transaction)",
                                () -> tx.jdbc().queryForObject(READ_BALANCE, BigDecimal.class, id), v -> Map.of("balance", v)));
                        return null;
                    });
                }))),
                WorkerSpec.local("writer", 1, rt -> {
                    turns.await(2, rt);
                    try {
                        return List.of(coordinator.execute(rt, spec(rt, IsolationLevel.READ_COMMITTED), tx ->
                                tx.write("account:NRR", "UPDATE balance = balance - " + delta + " and COMMIT",
                                        () -> tx.jdbc().update(UPDATE_BALANCE, delta, id), n -> Map.of("rows", n))));
                    } finally {
                        turns.done(2);
                    }
                })));
        boolean reproduced = first.get() != null && second.get() != null && first.get().compareTo(second.get()) != 0;
        Observation o = new Observation("NON_REPEATABLE_READ", level, level.effectiveInPostgres(), reproduced,
                reproduced ? "Same row, same transaction: first read " + first.get() + ", second read " + second.get()
                        : "Prevented: both reads returned " + first.get() + " although a concurrent transaction committed " + initial.subtract(delta),
                values("firstRead", first.get(), "secondRead", second.get(), "committedByWriter", initial.subtract(delta)),
                reproduced ? "READ COMMITTED takes a new snapshot for every statement."
                        : "REPEATABLE READ and SERIALIZABLE keep the snapshot taken by the first statement.");
        return new ProbeResult(o, List.of(ConsistencyValidator.row("account:NRR", "Non-repeatable read probe",
                "second read (vs first)", first.get(), second.get(), first.get())), state("NRR", initial), state("NRR", balance(id)));
    }
    // @snippet:end

    // @snippet:start dirty-read,non-repeatable-read,phantom-read,isolation-probe * java "Phantom read probe: count, concurrent insert + commit, count again"
    public ProbeResult phantomRead(ExperimentContext ctx, IsolationLevel level) {
        BigDecimal initial = ctx.config().initialValue();
        long id = seedAccount(ctx, "PHANTOM", initial);
        ctx.setup(s -> {
            for (int i = 0; i < 2; i++) {
                jdbc.update(INSERT_MOVEMENT, ctx.id(), id, new BigDecimal("150.00"), "seed", "seed");
            }
            return null;
        });
        ctx.event(EventType.DATA_PREPARED, "Phantom probe: 2 movements ≥ 100 on account PHANTOM", Map.of("accountId", id));
        TurnSequencer turns = new TurnSequencer(ctx.config().transactionTimeoutMs());
        AtomicReference<Long> first = new AtomicReference<>();
        AtomicReference<Long> second = new AtomicReference<>();
        ctx.runWorkers(List.of(
                WorkerSpec.local("reader", 0, rt -> List.of(coordinator.execute(rt, spec(rt, level), tx -> {
                    turns.step(1, rt, () -> {
                        first.set(tx.read("movements:PHANTOM", "first COUNT(*) WHERE amount ≥ 100",
                                () -> tx.jdbc().queryForObject(COUNT_MOVEMENTS, Long.class, rt.experimentId(), id), v -> Map.of("rows", v)));
                        return null;
                    });
                    turns.step(3, rt, () -> {
                        second.set(tx.read("movements:PHANTOM", "second COUNT(*) WHERE amount ≥ 100",
                                () -> tx.jdbc().queryForObject(COUNT_MOVEMENTS, Long.class, rt.experimentId(), id), v -> Map.of("rows", v)));
                        return null;
                    });
                }))),
                WorkerSpec.local("writer", 1, rt -> {
                    turns.await(2, rt);
                    try {
                        return List.of(coordinator.execute(rt, spec(rt, IsolationLevel.READ_COMMITTED), tx ->
                                tx.write("movements:PHANTOM", "INSERT movement of 500.00 and COMMIT",
                                        () -> tx.jdbc().update(INSERT_MOVEMENT, rt.experimentId(), id, new BigDecimal("500.00"),
                                                tx.txId(), tx.workerId()), n -> Map.of("rows", n))));
                    } finally {
                        turns.done(2);
                    }
                })));
        boolean reproduced = first.get() != null && second.get() != null && !first.get().equals(second.get());
        Observation o = new Observation("PHANTOM_READ", level, level.effectiveInPostgres(), reproduced,
                reproduced ? "The same range query returned " + first.get() + " then " + second.get() + " rows"
                        : "Prevented: both range queries returned " + first.get() + " rows despite a committed insert",
                values("firstCount", first.get(), "secondCount", second.get()),
                level.effectiveInPostgres() == IsolationLevel.REPEATABLE_READ && !reproduced
                        ? "The SQL standard allows phantoms at REPEATABLE READ, but PostgreSQL's snapshot prevents them."
                        : reproduced ? "READ COMMITTED sees rows committed between statements." : "Snapshot-based isolation.");
        return new ProbeResult(o, List.of(ConsistencyValidator.row("movements:PHANTOM", "Phantom read probe",
                "second count (vs first)", first.get(), second.get(), first.get())),
                new ResourceState("movements:PHANTOM", "Probe PHANTOM", "RANGE", Map.of("rows", 2)),
                new ResourceState("movements:PHANTOM", "Probe PHANTOM", "RANGE", Map.of("rows", movements(ctx.id(), id))));
    }
    // @snippet:end

    private static TxSpec spec(WorkerRuntime rt, IsolationLevel level) {
        return TxSpec.from(rt.config(), 1, 0, "probe").withIsolation(level).withRetries(0);
    }

    private BigDecimal balance(long id) {
        return jdbc.queryForObject(READ_BALANCE, BigDecimal.class, id);
    }

    private long movements(String experimentId, long id) {
        Long n = jdbc.queryForObject(COUNT_MOVEMENTS, Long.class, experimentId, id);
        return n == null ? 0 : n;
    }

    private static ResourceState state(String code, BigDecimal balance) {
        return new ResourceState("account:" + code, "Probe " + code, "ACCOUNT", Map.of("balance", balance));
    }

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}

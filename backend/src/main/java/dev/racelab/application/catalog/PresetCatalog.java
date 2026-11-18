package dev.racelab.application.catalog;

import dev.racelab.application.exception.ApiException;
import dev.racelab.domain.model.Category;
import dev.racelab.domain.model.Difficulty;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.ExperimentPreset;
import dev.racelab.domain.model.FailureMode;
import dev.racelab.domain.model.IsolationLevel;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.model.Topology;
import dev.racelab.scenario.support.ConfigBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.racelab.domain.model.Category.CONCURRENCY;
import static dev.racelab.domain.model.Category.DISTRIBUTED;
import static dev.racelab.domain.model.Category.ISOLATION;
import static dev.racelab.domain.model.Category.LOCKING;
import static dev.racelab.domain.model.Difficulty.ADVANCED;
import static dev.racelab.domain.model.Difficulty.BEGINNER;
import static dev.racelab.domain.model.Difficulty.INTERMEDIATE;
import static dev.racelab.domain.model.StrategyType.*;

/** The experiment catalog (section 6) — every entry runs a real scenario with a real strategy. */
@Component
public class PresetCatalog {

    static final List<StrategyType> BANK = List.of(NONE, OPTIMISTIC_LOCKING, PESSIMISTIC_LOCKING, ATOMIC_UPDATE,
            SERIALIZABLE, JAVA_SYNCHRONIZED, REENTRANT_LOCK, ADVISORY_LOCK, REDIS_LOCK);
    static final List<StrategyType> INVENTORY = List.of(NONE, ATOMIC_UNGUARDED, OPTIMISTIC_LOCKING, PESSIMISTIC_LOCKING,
            ATOMIC_UPDATE, SERIALIZABLE);
    static final List<StrategyType> SEATS = List.of(NONE, UNIQUE_CONSTRAINT, PESSIMISTIC_LOCKING, ATOMIC_UPDATE);
    static final List<StrategyType> PAYMENTS = List.of(NONE, UNIQUE_CONSTRAINT, IDEMPOTENCY_KEY);
    static final List<StrategyType> DEADLOCK = List.of(NONE, LOCK_ORDERING);
    static final List<StrategyType> SKEW = List.of(NONE, SERIALIZABLE, PESSIMISTIC_LOCKING, ADVISORY_LOCK);
    static final List<StrategyType> ORDERS = List.of(NONE, ATOMIC_CLAIM, SKIP_LOCKED, IDEMPOTENCY_KEY);
    static final List<StrategyType> ISO = List.of(ISOLATION_LEVEL);
    static final List<StrategyType> EXPIRY = List.of(REDIS_LOCK, FENCING_TOKEN);
    static final List<StrategyType> OUTBOX_S = List.of(NONE, OUTBOX, OUTBOX_NAIVE_CONSUMER);

    private final Map<String, ExperimentPreset> presets = new LinkedHashMap<>();

    public PresetCatalog() {
        ConfigBuilder bank = ConfigBuilder.defaults().resourceCode("ACC-A");

        // ------------------------------------------------------------------ Concurrency
        add("race-condition", "Race Condition", CONCURRENCY, BEGINNER,
                "Four transactions update the same balance at once; the outcome depends on who writes last.",
                "Quatro saques de R$ 100,00 concorrentes na Conta A (R$ 1.000,00).",
                "bank-withdrawal", NONE, BANK, ConfigBuilder.defaults().resourceCode("ACC-A").threads(4).transactions(4)
                        .operationValue("100.00").build(),
                "Final balance R$ 900,00 instead of R$ 600,00: three updates are lost.",
                "All transactions read 1000 before anyone writes; each writes 900. The database serializes the UPDATEs, "
                        + "but every one of them carries a value computed from a stale read.",
                List.of("Use an atomic update or optimistic locking", "Never compute a new value from an unprotected read"),
                null, "race", "lost-update");
        add("lost-update", "Lost Update", CONCURRENCY, BEGINNER,
                "Two withdrawals read the same balance and the second commit overwrites the first.",
                "Saldo inicial de R$ 1.000,00 e dois saques simultâneos de R$ 800,00.",
                "bank-withdrawal", NONE, BANK, ConfigBuilder.defaults().resourceCode("ACC-A").build(),
                "Both withdrawals are confirmed (R$ 1.600,00 paid) and the balance ends at R$ 200,00: INCONSISTENT.",
                "Both transactions read 1000, both validate 1000 ≥ 800, both write 200. Under READ COMMITTED PostgreSQL makes the "
                        + "second UPDATE wait for the first commit and then overwrites the row with the stale value.",
                List.of("Optimistic locking (@Version)", "Atomic conditional update", "Pessimistic locking"),
                1, "money", "lost-update");
        add("lost-update-optimistic", "Lost Update fixed with Optimistic Locking", CONCURRENCY, BEGINNER,
                "Same race, protected by JPA @Version.",
                "Saldo inicial de R$ 1.000,00 e dois saques simultâneos de R$ 800,00 com @Version.",
                "bank-withdrawal", OPTIMISTIC_LOCKING, BANK, ConfigBuilder.defaults().resourceCode("ACC-A").build(),
                "One withdrawal commits; the other gets a version conflict and is rolled back.",
                "Hibernate issues UPDATE … WHERE version = 0. The first commit moves the version to 1, so the second UPDATE affects 0 rows "
                        + "and Hibernate raises OptimisticLockException. With retries > 0 the loser re-reads 200 and is rejected by the business rule.",
                List.of("Enable bounded retries for legitimate operations"), 2, "money", "optimistic");
        add("deadlock", "Deadlock", CONCURRENCY, INTERMEDIATE,
                "Two transfers lock two accounts in opposite order.",
                "Transação A: conta 1 → conta 2. Transação B: conta 2 → conta 1.",
                "deadlock", NONE, DEADLOCK, ConfigBuilder.defaults().operationValue("100.00").retries(1).backoffMs(50).build(),
                "PostgreSQL detects the cycle (SQLSTATE 40P01) after deadlock_timeout and aborts one transaction; the bounded retry completes it.",
                "Each transaction holds one row lock and waits for the other one. PostgreSQL's deadlock detector breaks the wait-for cycle "
                        + "by aborting a victim, which is rolled back. Ordering the locks consistently removes the cycle.",
                List.of("Lock rows in a consistent order (e.g. ascending id)", "Keep transactions short", "Retry the victim"),
                3, "deadlock", "40P01");
        add("check-then-act", "Check-Then-Act", CONCURRENCY, BEGINNER,
                "Availability is checked and then acted upon in two separate steps.",
                "Dois usuários verificam que o assento A01 está livre e depois o reservam.",
                "seat-reservation", NONE, SEATS, ConfigBuilder.defaults().resourceCode("A01").build(),
                "Both users get a confirmed reservation for the same seat.",
                "The check (SELECT count(*)) and the act (INSERT) are separate statements; both checks see 0 reservations.",
                List.of("Unique constraint", "Atomic UPDATE … WHERE status = 'AVAILABLE'", "SELECT … FOR UPDATE on the seat"),
                null, "check-then-act");
        add("double-booking", "Double Booking", CONCURRENCY, BEGINNER,
                "Three customers try to book the same seat.",
                "Três clientes tentam reservar o assento A01 simultaneamente.",
                "seat-reservation", NONE, SEATS, ConfigBuilder.defaults().threads(3).transactions(3).resourceCode("A01").build(),
                "Three reservations for one seat.", "Without arbitration every customer wins the race.",
                List.of("Let the database arbitrate: unique constraint or atomic update"), null, "booking");
        add("overselling", "Inventory overselling", CONCURRENCY, BEGINNER,
                "Two purchases of 7 units against a stock of 10.",
                "Estoque inicial de 10 notebooks; duas compras simultâneas de 7 unidades.",
                "inventory", NONE, INVENTORY, ConfigBuilder.defaults().initialValue("10").operationValue("7").resourceCode("NOTEBOOK").build(),
                "Both orders are confirmed (14 units sold) and the stock shows 3: oversold and inconsistent.",
                "Both transactions read stock 10 and write 3 (lost update + oversell). With ATOMIC_UNGUARDED the stock becomes -4; "
                        + "with ATOMIC_UPDATE the second purchase is rejected.",
                List.of("UPDATE product SET stock = stock - :q WHERE stock >= :q"), 4, "inventory", "oversell");
        add("duplicate-payments", "Duplicate payments", CONCURRENCY, INTERMEDIATE,
                "A client retries a payment after a timeout while the first attempt is still processing.",
                "O cliente envia um pagamento, a resposta sofre timeout e o cliente tenta novamente.",
                "duplicate-payment", NONE, PAYMENTS, ConfigBuilder.defaults().operationValue("150.00").build(),
                "The customer is charged twice.",
                "The server cannot tell a retry from a new payment without an idempotency key.",
                List.of("Idempotency key persisted with a unique constraint"), null, "payments");
        add("unique-violation", "Uniqueness violation", CONCURRENCY, BEGINNER,
                "The database rejects the second reservation of a seat.",
                "Assento A01 protegido por UNIQUE (experiment_id, seat_id).",
                "seat-reservation", UNIQUE_CONSTRAINT, SEATS, ConfigBuilder.defaults().resourceCode("A01").build(),
                "One reservation; the other INSERT waits for the first commit and then fails with SQLSTATE 23505.",
                "PostgreSQL makes the second INSERT wait on the unique index until the first transaction finishes, then raises 23505.",
                List.of("Translate 23505 into a friendly 'seat already taken' message"), null, "23505");
        add("write-skew", "Write Skew", CONCURRENCY, ADVANCED,
                "Two doctors go off call at the same time; the rule requires at least one on call.",
                "Dois médicos de plantão; cada transação verifica que há 2 disponíveis e desativa um médico diferente.",
                "write-skew", NONE, SKEW, ConfigBuilder.defaults().isolation(IsolationLevel.REPEATABLE_READ).build(),
                "Under REPEATABLE READ (snapshot isolation) both commit and nobody is on call. Under SERIALIZABLE one aborts with 40001.",
                "Each transaction reads the same snapshot and updates a different row, so there is no row-level conflict. "
                        + "Only SSI (SERIALIZABLE) or explicit locks detect the dependency.",
                List.of("SERIALIZABLE + retry", "Lock all rows of the shift (FOR UPDATE)", "Advisory lock per shift"),
                7, "write-skew", "snapshot");
        add("resource-contention", "Resource contention", CONCURRENCY, INTERMEDIATE,
                "Eight workers hammer the same row with 40 withdrawals under pessimistic locking.",
                "Conta C (R$ 5.000,00) com 40 saques de R$ 50,00 por 8 workers.",
                "bank-withdrawal", PESSIMISTIC_LOCKING, BANK, ConfigBuilder.defaults().resourceCode("ACC-C").threads(8)
                        .transactions(40).initialValue("5000.00").operationValue("50.00").delayBeforeCommitMs(20).build(),
                "Consistent result, but most of the time is spent waiting for the row lock.",
                "A hot row serializes every writer. Compare lock wait and p95 latency with ATOMIC_UPDATE.",
                List.of("Shorten the critical section", "Use atomic updates", "Split hot rows"), null, "contention");

        // ------------------------------------------------------------------ Isolation
        add("dirty-read", "Dirty Read", ISOLATION, BEGINNER,
                "Can a transaction see data that another transaction has not committed?",
                "Uma transação altera o saldo para R$ 500,00 sem commit enquanto outra lê.",
                "dirty-read", ISOLATION_LEVEL, ISO, ConfigBuilder.defaults().isolation(IsolationLevel.READ_UNCOMMITTED).build(),
                "Never reproduced in PostgreSQL: READ UNCOMMITTED runs as READ COMMITTED; the reader sees 1000.",
                "PostgreSQL's MVCC never exposes uncommitted row versions. The SQL standard allows dirty reads at READ UNCOMMITTED; PostgreSQL does not.",
                List.of("Nothing to fix in PostgreSQL — but do not port code that relies on this to other databases"), null, "isolation");
        add("non-repeatable-read", "Non-Repeatable Read", ISOLATION, BEGINNER,
                "The same row is read twice in one transaction while another transaction commits a change in between.",
                "Leitura do saldo duas vezes na mesma transação, com um saque confirmado entre elas.",
                "non-repeatable-read", ISOLATION_LEVEL, ISO, ConfigBuilder.defaults().isolation(IsolationLevel.READ_COMMITTED).build(),
                "READ COMMITTED: the second read returns a different value. REPEATABLE READ / SERIALIZABLE: same value.",
                "READ COMMITTED takes a new snapshot per statement; REPEATABLE READ keeps the first snapshot for the whole transaction.",
                List.of("Use REPEATABLE READ for reports that read the same data several times"), null, "isolation");
        add("phantom-read", "Phantom Read", ISOLATION, INTERMEDIATE,
                "A range query is repeated while another transaction inserts a matching row.",
                "Contagem de movimentações de uma conta duas vezes, com uma inserção confirmada entre elas.",
                "phantom-read", ISOLATION_LEVEL, ISO, ConfigBuilder.defaults().isolation(IsolationLevel.READ_COMMITTED).build(),
                "READ COMMITTED: the count changes. REPEATABLE READ in PostgreSQL: no phantom (snapshot).",
                "The SQL standard allows phantoms at REPEATABLE READ, but PostgreSQL's snapshot prevents them.",
                List.of("REPEATABLE READ or SERIALIZABLE for consistent range reads"), null, "isolation");
        add("isolation-read-committed", "Read Committed", ISOLATION, BEGINNER,
                "Runs the dirty, non-repeatable and phantom read probes at READ COMMITTED.",
                "Sondas de leitura executadas em READ COMMITTED.",
                "isolation-probe", ISOLATION_LEVEL, ISO, ConfigBuilder.defaults().isolation(IsolationLevel.READ_COMMITTED).build(),
                "No dirty read; non-repeatable and phantom reads reproduced.",
                "Default level of PostgreSQL: every statement sees data committed before it started.", List.of(), null, "isolation");
        add("isolation-repeatable-read", "Repeatable Read", ISOLATION, INTERMEDIATE,
                "Runs the read probes at REPEATABLE READ.", "Sondas de leitura executadas em REPEATABLE READ.",
                "isolation-probe", ISOLATION_LEVEL, ISO, ConfigBuilder.defaults().isolation(IsolationLevel.REPEATABLE_READ).build(),
                "No dirty, non-repeatable or phantom read (snapshot isolation). Write skew is still possible — see the Write Skew experiment.",
                "PostgreSQL implements REPEATABLE READ as snapshot isolation.", List.of(), null, "isolation");
        add("isolation-serializable", "Serializable", ISOLATION, ADVANCED,
                "Runs the read probes at SERIALIZABLE.", "Sondas de leitura executadas em SERIALIZABLE.",
                "isolation-probe", ISOLATION_LEVEL, ISO, ConfigBuilder.defaults().isolation(IsolationLevel.SERIALIZABLE).build(),
                "No read phenomena; conflicting read/write patterns may abort with 40001.",
                "Serializable Snapshot Isolation tracks read/write dependencies and aborts dangerous structures.", List.of(), null, "isolation");
        add("isolation-read-uncommitted", "Read Uncommitted", ISOLATION, BEGINNER,
                "Runs the read probes at READ UNCOMMITTED.", "Sondas de leitura executadas em READ UNCOMMITTED.",
                "isolation-probe", ISOLATION_LEVEL, ISO, ConfigBuilder.defaults().isolation(IsolationLevel.READ_UNCOMMITTED).build(),
                "Identical to READ COMMITTED: PostgreSQL never performs dirty reads.",
                "PostgreSQL accepts the syntax but runs READ COMMITTED.", List.of(), null, "isolation");

        // ------------------------------------------------------------------ Locking
        add("locking-none", "No concurrency control", LOCKING, BEGINNER, "Baseline without protection.",
                "Dois saques de R$ 800,00 sem proteção.", "bank-withdrawal", NONE, BANK, bank.build(),
                "Lost update.", "Baseline for comparison.", List.of(), null, "baseline");
        add("locking-optimistic", "Optimistic Locking", LOCKING, BEGINNER, "JPA @Version with optional retries.",
                "Dois saques de R$ 800,00 com @Version e 1 retry.", "bank-withdrawal", OPTIMISTIC_LOCKING, BANK,
                ConfigBuilder.defaults().resourceCode("ACC-A").retries(1).build(),
                "One commit, one version conflict; the retry re-reads 200 and is rejected by the business rule.",
                "Conflicts are detected at write time; the loser retries in a new transaction.", List.of(), null, "optimistic");
        add("locking-pessimistic", "Pessimistic Locking", LOCKING, BEGINNER, "SELECT … FOR UPDATE via @Lock(PESSIMISTIC_WRITE).",
                "Dois saques de R$ 800,00 com lock de linha.", "bank-withdrawal", PESSIMISTIC_LOCKING, BANK,
                ConfigBuilder.defaults().resourceCode("ACC-A").delayBeforeCommitMs(300).build(),
                "The second transaction waits (observed in pg_blocking_pids), then reads 200 and is rejected.",
                "The lock is taken at read time and held until commit.", List.of(), null, "pessimistic");
        add("locking-atomic", "Atomic Update", LOCKING, BEGINNER, "Conditional UPDATE with row-count check.",
                "Dois saques de R$ 800,00 com UPDATE condicional.", "bank-withdrawal", ATOMIC_UPDATE, BANK, bank.build(),
                "One UPDATE affects 1 row, the other 0 rows (rejected).",
                "The database re-evaluates the WHERE clause on the latest committed version.", List.of(), null, "atomic");
        add("locking-synchronized", "Java synchronized", LOCKING, BEGINNER, "synchronized around the whole transaction.",
                "Dois saques de R$ 800,00 na mesma JVM.", "bank-withdrawal", JAVA_SYNCHRONIZED, BANK, bank.build(),
                "Consistent in a single JVM.", "The monitor belongs to this JVM only — see the Distributed Lab.", List.of(), null, "jvm");
        add("locking-reentrant", "ReentrantLock", LOCKING, INTERMEDIATE, "tryLock with timeout, unlock in finally.",
                "Dois saques de R$ 800,00 com ReentrantLock.", "bank-withdrawal", REENTRANT_LOCK, BANK,
                ConfigBuilder.defaults().resourceCode("ACC-A").delayBeforeCommitMs(200).build(),
                "Consistent; contention visible as LOCK_WAITING.", "JVM-local, with timeouts.", List.of(), null, "jvm");
        add("locking-advisory", "PostgreSQL Advisory Lock", LOCKING, INTERMEDIATE, "pg_advisory_xact_lock(account id).",
                "Dois saques de R$ 800,00 com advisory lock.", "bank-withdrawal", ADVISORY_LOCK, BANK,
                ConfigBuilder.defaults().resourceCode("ACC-A").delayBeforeCommitMs(200).build(),
                "Consistent; the wait is observed in pg_locks (locktype advisory).",
                "Application-defined lock managed by PostgreSQL, released at commit/rollback.", List.of(), null, "advisory");
        add("locking-redis", "Distributed Lock with Redis", LOCKING, INTERMEDIATE, "SET NX PX + safe release.",
                "Dois saques de R$ 800,00 coordenados por Redis.", "bank-withdrawal", REDIS_LOCK, BANK,
                ConfigBuilder.defaults().resourceCode("ACC-A").lockTtlMs(5000).build(),
                "Consistent while the lease does not expire.", "See Lock Expiration in the Distributed Lab.", List.of(), null, "redis");

        // ------------------------------------------------------------------ Distributed
        add("idempotency", "Idempotency", DISTRIBUTED, INTERMEDIATE,
                "Two requests with the same idempotency key.", "Pagamento repetido com a mesma Idempotency-Key.",
                "duplicate-payment", IDEMPOTENCY_KEY, PAYMENTS, ConfigBuilder.defaults().operationValue("150.00").build(),
                "Only one payment; the retry receives the original response.",
                "The retry's INSERT … ON CONFLICT DO NOTHING waits on the unique index until the first request commits, "
                        + "then it reads the stored response.", List.of(), 5, "idempotency");
        add("retry-duplicate", "Retry with duplication risk", DISTRIBUTED, BEGINNER,
                "Client retry without idempotency.", "Timeout do cliente seguido de retry sem chave de idempotência.",
                "duplicate-payment", NONE, PAYMENTS, ConfigBuilder.defaults().operationValue("150.00").build(),
                "Two payments for one purchase.", "At-least-once retries need idempotent receivers.", List.of(), null, "retry");
        add("distributed-lock", "Distributed Lock", DISTRIBUTED, INTERMEDIATE,
                "Redis lock coordinating workers on two JVMs (requires the distributed profile) or in one JVM.",
                "Saques coordenados por Redis entre instâncias.", "bank-withdrawal", REDIS_LOCK, BANK,
                ConfigBuilder.defaults().resourceCode("ACC-A").lockTtlMs(5000).build(),
                "Consistent while leases are valid.", "Switch topology to DISTRIBUTED when both backends are running.",
                List.of(), null, "redis");
        add("message-processing", "Concurrent message processing", DISTRIBUTED, INTERMEDIATE,
                "Two workers consume the same pending orders.", "Dois workers tentam processar os mesmos pedidos pendentes.",
                "order-processing", NONE, ORDERS, ConfigBuilder.defaults().resourceCount(3).build(),
                "Some orders are shipped twice.", "Both workers pick the oldest pending order.",
                List.of("SKIP LOCKED", "Atomic claim", "Idempotent consumer"), null, "queue");
        add("transactional-outbox", "Transactional Outbox", DISTRIBUTED, ADVANCED,
                "Outbox relay crashes after publishing but before acknowledging.",
                "Pedido gravado com evento na tabela outbox; relay falha após publicar e antes de confirmar.",
                "outbox", OUTBOX, OUTBOX_S, ConfigBuilder.defaults().transactions(3).failureMode(FailureMode.RELAY_CRASH_BEFORE_ACK).build(),
                "No event is lost; one event is delivered twice; the idempotent consumer processes it once.",
                "Outbox gives at-least-once delivery, not exactly-once: deduplication belongs to the consumer.",
                List.of(), 8, "outbox");
        add("partial-failure", "Partial failure between services", DISTRIBUTED, INTERMEDIATE,
                "The service commits the order and crashes before publishing the event.",
                "Pedido gravado; o processo cai antes de publicar o evento (dual write).",
                "outbox", NONE, OUTBOX_S, ConfigBuilder.defaults().transactions(3).failureMode(FailureMode.CRASH_AFTER_COMMIT).build(),
                "Orders exist without their events: downstream services never hear about them.",
                "Two independent systems (database, broker) cannot be updated atomically without an outbox.",
                List.of("Transactional outbox"), null, "dual-write");
        add("lock-expiration", "Lock expiration", DISTRIBUTED, ADVANCED,
                "The Redis lease expires while its owner is still working.",
                "O lock Redis expira durante o processamento e um segundo cliente o adquire.",
                "lock-expiration", REDIS_LOCK, EXPIRY, ConfigBuilder.defaults().lockTtlMs(800).operationValue("300.00").transactionTimeoutMs(15000).build(),
                "Two owners write the same account: inconsistent. With FENCING_TOKEN the stale write is rejected.",
                "A lease is a promise with a deadline. After it expires the old owner does not know it lost the lock.",
                List.of("Fencing tokens validated by the resource"), null, "redis", "fencing");
        add("duplicate-events", "Duplicate events", DISTRIBUTED, INTERMEDIATE,
                "Redelivered outbox events consumed by a non-idempotent consumer.",
                "Evento reenviado após falha do relay; consumidor sem deduplicação.",
                "outbox", OUTBOX_NAIVE_CONSUMER, OUTBOX_S, ConfigBuilder.defaults().transactions(3)
                        .failureMode(FailureMode.RELAY_CRASH_BEFORE_ACK).build(),
                "A side effect is applied twice.", "Redelivery is normal in at-least-once systems.",
                List.of("Idempotent consumer with processed_event table"), null, "duplicates");
        add("jvm-synchronized", "Independent JVMs and synchronized", DISTRIBUTED, ADVANCED,
                "synchronized cannot protect two backend instances.",
                "Dois saques de R$ 800,00 executados em JVMs diferentes (perfil distributed).",
                "bank-withdrawal", JAVA_SYNCHRONIZED, BANK, ConfigBuilder.defaults().resourceCode("ACC-A")
                        .topology(Topology.DISTRIBUTED).build(),
                "Lost update: each JVM has its own monitor.",
                "worker-1 runs on the primary backend and worker-2 on the secondary backend; both acquire 'the' lock.",
                List.of("Advisory lock", "Pessimistic lock", "Atomic update"), 6, "jvm", "distributed");
    }

    private void add(String id, String name, Category category, Difficulty difficulty, String problem, String business,
                     String scenarioId, StrategyType strategy, List<StrategyType> available, ExperimentConfiguration cfg,
                     String expected, String explanation, List<String> recommendations, Integer demo, String... tags) {
        presets.put(id, new ExperimentPreset(id, name, category, difficulty, problem, business, scenarioId, strategy,
                available, cfg, expected, explanation, recommendations, demo, List.of(tags)));
    }

    public List<ExperimentPreset> all() {
        return new ArrayList<>(presets.values());
    }

    public ExperimentPreset get(String id) {
        ExperimentPreset p = presets.get(id);
        if (p == null) {
            throw ApiException.notFound("Preset '" + id + "'");
        }
        return p;
    }
}

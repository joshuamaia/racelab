package dev.racelab.application.engine;

import dev.racelab.domain.model.StrategyDescriptor;
import dev.racelab.domain.model.StrategyType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Catalog of the concurrency-control strategies (Strategy Pattern: each scenario implements the ones it supports). */
@Component
public class StrategyRegistry {

    private final Map<StrategyType, StrategyDescriptor> descriptors = new EnumMap<>(StrategyType.class);

    public StrategyRegistry() {
        add(StrategyType.NONE, "No concurrency control", "NONE", "DATABASE",
                "Read, validate in Java, write the computed value. Correct only when nobody else touches the row.",
                List.of("Simplest code", "No waits"),
                List.of("Lost updates and check-then-act races under concurrency", "Absence of anomalies in one run proves nothing"));
        add(StrategyType.OPTIMISTIC_LOCKING, "Optimistic Locking (@Version)", "OPTIMISTIC", "DATABASE",
                "JPA @Version: UPDATE … SET version = version + 1 WHERE id = ? AND version = ?; 0 rows ⇒ conflict.",
                List.of("No lock held while the user/thread thinks", "Great when conflicts are rare", "Detects lost updates"),
                List.of("Losers must retry in a NEW transaction", "Wasted work under high contention"));
        add(StrategyType.PESSIMISTIC_LOCKING, "Pessimistic Locking (FOR UPDATE)", "PESSIMISTIC", "DATABASE",
                "@Lock(PESSIMISTIC_WRITE) / SELECT … FOR UPDATE: the row lock is taken at read time and held until commit.",
                List.of("Serializes access to the row", "No retry logic for simple read-modify-write"),
                List.of("Waiting transactions hold connections", "Deadlocks if lock order is inconsistent", "Throughput drops with long transactions"));
        add(StrategyType.ATOMIC_UPDATE, "Atomic conditional update", "ATOMIC", "DATABASE",
                "UPDATE account SET balance = balance - :amount WHERE id = :id AND balance >= :amount — check and write in one statement.",
                List.of("Single round-trip", "Invariant enforced by the database", "Short row-lock duration"),
                List.of("Only for rules expressible in one statement", "Must check the affected row count"));
        add(StrategyType.ATOMIC_UNGUARDED, "Atomic update without guard", "ATOMIC", "DATABASE",
                "UPDATE product SET stock = stock - :q after a separate check: no lost update, but the business rule is not protected.",
                List.of("No lost update"),
                List.of("Check-then-act race: invariant (stock ≥ 0) can be broken"));
        add(StrategyType.SERIALIZABLE, "Serializable isolation", "ISOLATION", "DATABASE",
                "Run at SERIALIZABLE (PostgreSQL SSI): anomalies abort with SQLSTATE 40001 and must be retried.",
                List.of("Protects complex invariants (write skew) without explicit locks", "Code stays simple"),
                List.of("Requires retry of the whole transaction", "More aborts under contention"));
        add(StrategyType.JAVA_SYNCHRONIZED, "Java synchronized", "JVM", "JVM",
                "synchronized(monitor) around the whole transaction (including commit).",
                List.of("Trivial to write", "Works within ONE JVM"),
                List.of("Does not protect other JVMs/instances", "Blocks platform threads", "Must include the commit"));
        add(StrategyType.REENTRANT_LOCK, "ReentrantLock (tryLock + timeout)", "JVM", "JVM",
                "java.util.concurrent.locks.ReentrantLock with tryLock(timeout) and unlock() in finally.",
                List.of("Timeouts and fairness", "Explicit contention visibility"),
                List.of("Still JVM-local", "Forgetting unlock in finally leaks the lock"));
        add(StrategyType.ADVISORY_LOCK, "PostgreSQL advisory lock", "DATABASE_LOCK", "DATABASE",
                "pg_advisory_xact_lock(key): application-defined lock held by the database until the transaction ends.",
                List.of("Works across JVMs/instances", "Released automatically at commit/rollback"),
                List.of("Key space is application-defined", "Only protects code that takes the same lock"));
        add(StrategyType.REDIS_LOCK, "Redis distributed lock", "DISTRIBUTED_LOCK", "DISTRIBUTED",
                "SET key owner NX PX ttl; release with an atomic compare-and-delete Lua script.",
                List.of("Coordinates any process that can reach Redis", "TTL avoids eternal locks"),
                List.of("Lease may expire while the owner still works", "Not a consistency guarantee by itself — combine with fencing/DB guards"));
        add(StrategyType.FENCING_TOKEN, "Redis lock + fencing token", "DISTRIBUTED_LOCK", "DISTRIBUTED",
                "Every lease gets a monotonically increasing token; the database rejects writes carrying an older token.",
                List.of("Stale owners cannot overwrite newer writes"),
                List.of("The protected resource must validate tokens"));
        add(StrategyType.UNIQUE_CONSTRAINT, "Unique constraint", "CONSTRAINT", "DATABASE",
                "Let the database reject the duplicate (SQLSTATE 23505).",
                List.of("Bullet-proof for uniqueness invariants", "Works across every writer"),
                List.of("Only uniqueness rules", "Caller must translate the error"));
        add(StrategyType.IDEMPOTENCY_KEY, "Idempotency key", "IDEMPOTENCY", "DATABASE",
                "Persist the key (unique) with the request hash and the response; repeated requests get the original response.",
                List.of("Safe retries", "Detects key reuse with a different payload"),
                List.of("Storage and expiry policy for keys", "Concurrent duplicates wait for the first one"));
        add(StrategyType.LOCK_ORDERING, "Consistent lock ordering", "PESSIMISTIC", "DATABASE",
                "Acquire row locks always in the same order (e.g. ascending id) to make wait-for cycles impossible.",
                List.of("Eliminates this class of deadlocks"),
                List.of("Requires knowing all resources upfront"));
        add(StrategyType.ATOMIC_CLAIM, "Atomic claim", "ATOMIC", "DATABASE",
                "UPDATE work_item SET status='PROCESSING', owner=? WHERE id=? AND status='PENDING' — exactly one claimer wins.",
                List.of("Exactly one owner per item"),
                List.of("Losers waste a round-trip", "Needs recovery for crashed owners"));
        add(StrategyType.SKIP_LOCKED, "SELECT … FOR UPDATE SKIP LOCKED", "PESSIMISTIC", "DATABASE",
                "Workers lock different rows and skip the ones already locked — a queue without contention.",
                List.of("No waiting between workers", "Natural work distribution"),
                List.of("Non-deterministic order", "Only for queue-like workloads"));
        add(StrategyType.OUTBOX, "Transactional outbox + idempotent consumer", "MESSAGING", "DISTRIBUTED",
                "Write the business row and the event in the same transaction; a relay publishes pending events; consumers deduplicate.",
                List.of("No lost or ghost events", "At-least-once delivery with effectively-once processing"),
                List.of("Duplicates still happen on the wire (not exactly-once)", "Extra relay process and table"));
        add(StrategyType.OUTBOX_NAIVE_CONSUMER, "Outbox + non-idempotent consumer", "MESSAGING", "DISTRIBUTED",
                "Outbox delivery with a consumer that applies every message it receives.",
                List.of("No lost events"),
                List.of("Redeliveries produce duplicate side effects"));
        add(StrategyType.ISOLATION_LEVEL, "Configured isolation level", "ISOLATION", "DATABASE",
                "Runs the probe at the isolation level selected in the configuration.",
                List.of("Shows exactly what PostgreSQL guarantees at each level"),
                List.of("Higher levels may abort transactions (40001)"));
    }

    private void add(StrategyType id, String name, String family, String scope, String description,
                     List<String> advantages, List<String> limitations) {
        descriptors.put(id, new StrategyDescriptor(id, name, family, scope, description, advantages, limitations));
    }

    public List<StrategyDescriptor> all() {
        return List.copyOf(descriptors.values());
    }

    public StrategyDescriptor get(StrategyType type) {
        return descriptors.get(type);
    }
}

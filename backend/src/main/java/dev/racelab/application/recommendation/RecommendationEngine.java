package dev.racelab.application.recommendation;

import dev.racelab.domain.model.ErrorType;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.Metrics;
import dev.racelab.domain.model.Observation;
import dev.racelab.domain.model.Recommendation;
import dev.racelab.domain.model.StrategyType;
import dev.racelab.domain.model.Topology;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.Violation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.racelab.domain.model.StrategyType.*;

/**
 * Deterministic, rule-based recommendations. Every rule is triggered by something that really happened in
 * the experiment (violations, SQLSTATEs, measured lock waits…); no external AI service is involved.
 */
@Component
public class RecommendationEngine {

    public record Input(String scenarioId, StrategyType strategy, ExperimentConfiguration configuration,
                        List<Violation> violations, Metrics metrics, List<Observation> observations,
                        List<TransactionOutcome> outcomes) {
    }

    public List<Recommendation> recommend(Input in) {
        Map<String, Recommendation> out = new LinkedHashMap<>();
        boolean inconsistent = !in.violations().isEmpty();
        String s = in.scenarioId();

        if (inconsistent && (s.equals("bank-withdrawal") || s.equals("inventory"))
                && in.configuration().topology() != Topology.DISTRIBUTED) {
            add(out, new Recommendation("lost-update", "Make the read-modify-write atomic or versioned",
                    "Lost update / check-then-act detected",
                    "Several transactions computed the new value from the same stale read and the last commit won. "
                            + "The invariant was broken even though every transaction committed successfully.",
                    List.of("Atomic conditional UPDATE keeps check and write in one statement",
                            "Optimistic locking detects the conflict without holding locks",
                            "Pessimistic locking serializes access in high-contention hot spots"),
                    List.of("Atomic updates only fit rules expressible in SQL",
                            "Optimistic locking requires retries in a new transaction",
                            "Pessimistic locking reduces throughput and can deadlock"),
                    List.of(ATOMIC_UPDATE, OPTIMISTIC_LOCKING, PESSIMISTIC_LOCKING), 1));
        }
        if (inconsistent && s.equals("inventory") && in.strategy() == ATOMIC_UNGUARDED) {
            add(out, new Recommendation("oversell-guard", "Put the business rule inside the UPDATE",
                    "Negative stock / overselling",
                    "Decrementing atomically avoids lost updates but the stock check happened before, in another statement.",
                    List.of("UPDATE … WHERE stock >= :qty makes the database enforce the rule",
                            "A CHECK (stock >= 0) constraint is a last line of defence"),
                    List.of("Callers must handle 0 affected rows as 'sold out'"),
                    List.of(ATOMIC_UPDATE), 1));
        }
        if (in.metrics().deadlocks() > 0) {
            add(out, new Recommendation("deadlock", "Acquire locks in a consistent order",
                    "Deadlock detected by PostgreSQL (40P01)",
                    "Two transactions waited for each other's row locks. PostgreSQL broke the cycle by aborting one of them.",
                    List.of("Ordering locks (e.g. by ascending id) makes cycles impossible",
                            "Shorter transactions shrink the window for cycles",
                            "A bounded retry with backoff recovers the aborted victim",
                            "Proper indexes avoid locking more rows than needed"),
                    List.of("Ordering requires knowing all resources upfront",
                            "Retries add latency and must be idempotent"),
                    List.of(LOCK_ORDERING), 1));
        }
        if (inconsistent && s.equals("duplicate-payment")) {
            add(out, new Recommendation("idempotency", "Make the payment endpoint idempotent",
                    "Duplicate payment after client retry",
                    "The client could not distinguish 'timed out but processed' from 'failed', so it retried and the server charged twice.",
                    List.of("Persist an idempotency key with a UNIQUE constraint",
                            "Store the request hash and reject reuse with a different payload",
                            "Return the original response to repeated requests"),
                    List.of("Keys need retention/expiry", "Concurrent duplicates wait for the first request"),
                    List.of(IDEMPOTENCY_KEY, UNIQUE_CONSTRAINT), 1));
        }
        if (inconsistent && s.equals("seat-reservation")) {
            add(out, new Recommendation("double-booking", "Let the database arbitrate the seat",
                    "Double booking",
                    "Both users checked availability before either inserted a reservation (check-then-act).",
                    List.of("A UNIQUE constraint on (seat) rejects the second booking (23505)",
                            "An atomic UPDATE seat SET status='RESERVED' WHERE status='AVAILABLE' lets one winner through",
                            "SELECT … FOR UPDATE on the seat serializes the check"),
                    List.of("Constraint errors must be translated into a friendly message"),
                    List.of(UNIQUE_CONSTRAINT, ATOMIC_UPDATE, PESSIMISTIC_LOCKING), 1));
        }
        if (inconsistent && s.equals("write-skew")) {
            add(out, new Recommendation("write-skew", "Protect the invariant that spans several rows",
                    "Write skew under snapshot isolation",
                    "Each transaction read a consistent snapshot and changed a DIFFERENT row, so no row-level conflict was detected.",
                    List.of("SERIALIZABLE (SSI) aborts one transaction with 40001",
                            "Materialize the conflict: SELECT … FOR UPDATE on every row of the shift",
                            "An advisory lock per shift serializes the decision"),
                    List.of("Serializable requires retries", "Explicit locks reduce concurrency"),
                    List.of(SERIALIZABLE, PESSIMISTIC_LOCKING, ADVISORY_LOCK), 1));
        }
        if (inconsistent && s.equals("order-processing")) {
            add(out, new Recommendation("duplicate-processing", "Claim work items atomically",
                    "Same order processed by more than one worker",
                    "Workers selected the same pending order before any of them marked it as taken.",
                    List.of("FOR UPDATE SKIP LOCKED gives each worker a different row without waiting",
                            "An atomic claim UPDATE … WHERE status='PENDING' admits one owner",
                            "An idempotent consumer (unique processed key) neutralizes duplicates"),
                    List.of("SKIP LOCKED changes ordering", "Claims need recovery of crashed workers"),
                    List.of(SKIP_LOCKED, ATOMIC_CLAIM, IDEMPOTENCY_KEY), 1));
        }
        if (inconsistent && s.equals("outbox")) {
            boolean duplicates = in.violations().stream().anyMatch(v -> v.rule().contains("processed at most once"));
            add(out, duplicates
                    ? new Recommendation("idempotent-consumer", "Deduplicate in the consumer",
                    "Duplicate event processing", "The outbox guarantees at-least-once delivery: a relay crash before the "
                    + "acknowledgement republished the event and the consumer applied it twice.",
                    List.of("Record processed event ids with a UNIQUE constraint in the consumer transaction"),
                    List.of("Consumer needs its own storage"), List.of(OUTBOX), 1)
                    : new Recommendation("transactional-outbox", "Write the event in the same transaction as the data",
                    "Lost or ghost event (dual write)", "Database commit and message publication are two separate systems; a crash "
                    + "between them loses the event (or publishes an event whose data never committed).",
                    List.of("Transactional outbox: data + event commit atomically", "A relay publishes pending events with retries"),
                    List.of("Extra table and relay process", "Delivery becomes at-least-once"), List.of(OUTBOX), 1));
        }
        if (inconsistent && in.configuration().topology() == Topology.DISTRIBUTED
                && (in.strategy() == JAVA_SYNCHRONIZED || in.strategy() == REENTRANT_LOCK || in.strategy() == NONE)) {
            add(out, new Recommendation("jvm-lock-distributed", "Use a lock shared by every instance",
                    "JVM-local lock across independent JVMs",
                    "synchronized/ReentrantLock only coordinate threads of the SAME JVM. The other instance had its own monitor.",
                    List.of("Database row locks or advisory locks coordinate every instance",
                            "Atomic updates need no lock at all",
                            "Redis locks work across processes (combine with fencing tokens)"),
                    List.of("Distributed locks add latency and failure modes"),
                    List.of(ADVISORY_LOCK, PESSIMISTIC_LOCKING, ATOMIC_UPDATE, REDIS_LOCK), 1));
        }
        if (inconsistent && s.equals("lock-expiration")) {
            add(out, new Recommendation("fencing", "Never trust a lease alone: use fencing tokens",
                    "Two owners of the same Redis lock",
                    "The lease expired while the first owner was still working (GC pause, slow I/O). A second client acquired "
                            + "the lock and both wrote the resource.",
                    List.of("Fencing tokens let the resource reject stale owners",
                            "Size TTLs well above the worst-case critical section and renew leases",
                            "Keep a database-level guard (version/atomic update) on the protected data"),
                    List.of("The protected resource must validate tokens"),
                    List.of(FENCING_TOKEN, OPTIMISTIC_LOCKING), 1));
        }
        int optimistic = in.metrics().optimisticConflicts();
        if (optimistic > 0 && in.metrics().retries() == 0) {
            add(out, new Recommendation("optimistic-retry", "Retry version conflicts in a new transaction",
                    optimistic + " optimistic lock conflict(s) without retry",
                    "Optimistic locking prevented the lost update, but the losing operations were simply aborted.",
                    List.of("A bounded retry (re-read, re-validate, re-write) completes legitimate operations"),
                    List.of("Under high contention retries waste work — consider pessimistic locking or atomic updates"),
                    List.of(OPTIMISTIC_LOCKING, PESSIMISTIC_LOCKING, ATOMIC_UPDATE), 2));
        }
        if (in.metrics().serializationFailures() > 0) {
            add(out, new Recommendation("serialization-retry", "Serializable transactions must be retried",
                    in.metrics().serializationFailures() + " serialization failure(s) (40001)",
                    "PostgreSQL aborted transactions whose effects could not be serialized. This is the expected behaviour, "
                            + "not a bug: the application must retry the whole transaction.",
                    List.of("Generic retry wrapper for SQLSTATE 40001", "Keep serializable transactions short"),
                    List.of("Retries add latency"), List.of(SERIALIZABLE), 2));
        }
        if (in.metrics().lockTimeouts() > 0) {
            add(out, new Recommendation("lock-timeout", "Reduce lock hold time or tune lock_timeout",
                    in.metrics().lockTimeouts() + " lock timeout(s) (55P03)",
                    "Transactions gave up waiting for a lock held too long by another transaction.",
                    List.of("Move slow work out of the critical section", "Fail fast with a meaningful error"),
                    List.of("Lower timeouts increase failures; higher ones increase latency"),
                    List.of(ATOMIC_UPDATE, OPTIMISTIC_LOCKING), 2));
        }
        Metrics mt = in.metrics();
        if (mt.totalTimeMs() > 0 && mt.lockWaitTimeMs() > 0 && in.outcomes().size() >= 4
                && mt.lockWaitTimeMs() > 0.3 * mt.totalTimeMs() * Math.max(1, in.configuration().threads()) / 2.0) {
            add(out, new Recommendation("contention", "High lock contention",
                    "Lock wait time " + mt.lockWaitTimeMs() + " ms over " + mt.totalTimeMs() + " ms of execution",
                    "A large share of the execution time was spent waiting for locks on the same resource.",
                    List.of("Reduce the time spent inside the critical section",
                            "Prefer atomic updates (shortest possible lock)",
                            "Use finer-grained resources (e.g. split hot rows)"),
                    List.of("Finer granularity complicates invariants spanning resources"),
                    List.of(ATOMIC_UPDATE, OPTIMISTIC_LOCKING), 2));
        }
        for (Observation o : in.observations()) {
            if (o.isolationLevel() != o.effectiveIsolationLevel()) {
                add(out, new Recommendation("read-uncommitted", "READ UNCOMMITTED behaves as READ COMMITTED in PostgreSQL",
                        "Isolation level mapping", "PostgreSQL never exposes uncommitted data; requesting READ UNCOMMITTED "
                        + "silently runs READ COMMITTED.",
                        List.of("Do not rely on dirty reads for 'performance' in PostgreSQL"), List.of(), List.of(ISOLATION_LEVEL), 3));
            }
            if (o.reproduced() && !"WRITE_SKEW".equals(o.phenomenon())) {
                add(out, new Recommendation("isolation-" + o.phenomenon().toLowerCase(),
                        "Raise the isolation level if the transaction needs stable reads",
                        o.phenomenon() + " observed at " + o.effectiveIsolationLevel(),
                        "Within one transaction, repeated reads returned different results because other transactions committed in between.",
                        List.of("REPEATABLE READ gives the transaction a single snapshot", "Or lock the rows read (FOR SHARE/UPDATE)"),
                        List.of("Higher isolation can raise 40001 failures that require retries"),
                        List.of(ISOLATION_LEVEL, SERIALIZABLE), 2));
            }
        }
        boolean anyInternal = in.outcomes().stream().anyMatch(o -> o.errorType() == ErrorType.INTERNAL);
        if (anyInternal) {
            add(out, new Recommendation("internal-failure", "Investigate unexpected failures",
                    "Unexpected technical failure", "At least one operation failed for a reason that is not part of the experiment.",
                    List.of("Check the backend logs using the correlation id"), List.of(), List.of(), 1));
        }
        if (!inconsistent && in.strategy() == NONE && !s.startsWith("isolation") && !s.equals("dirty-read")
                && !s.equals("non-repeatable-read") && !s.equals("phantom-read")) {
            add(out, new Recommendation("no-anomaly-no-proof", "A clean run does not prove the code is safe",
                    "No inconsistency in this run without concurrency control",
                    "Race conditions depend on timing. Use the coordinated mode, more threads or delays to widen the window.",
                    List.of("Coordinated mode reproduces the interleaving deterministically"), List.of(), List.of(), 3));
        }
        List<Recommendation> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparingInt(Recommendation::priority));
        return list;
    }

    private static void add(Map<String, Recommendation> out, Recommendation r) {
        out.putIfAbsent(r.id(), r);
    }
}

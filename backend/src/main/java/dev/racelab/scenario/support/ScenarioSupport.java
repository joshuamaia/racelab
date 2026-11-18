package dev.racelab.scenario.support;

import dev.racelab.application.engine.ConsistencyValidator;
import dev.racelab.application.engine.Experiment;
import dev.racelab.application.engine.TransactionCoordinator;
import dev.racelab.application.engine.WorkerRuntime;
import dev.racelab.domain.model.ExperimentConfiguration;
import dev.racelab.domain.model.TransactionOutcome;
import dev.racelab.domain.model.TxStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Common helpers of the scenario implementations. */
public abstract class ScenarioSupport implements Experiment {

    protected final JdbcTemplate jdbc;
    protected final TransactionCoordinator coordinator;
    protected final ConsistencyValidator validator;

    protected ScenarioSupport(JdbcTemplate jdbc, TransactionCoordinator coordinator, ConsistencyValidator validator) {
        this.jdbc = jdbc;
        this.coordinator = coordinator;
        this.validator = validator;
    }

    /**
     * Distributes {@code transactions} operations round-robin across {@code threads} workers and runs the ones
     * of this worker. The callback receives (operation number for this worker, round).
     */
    protected static List<TransactionOutcome> perOperation(WorkerRuntime rt,
                                                           BiFunction<Integer, Integer, TransactionOutcome> op) {
        return perOperation(rt, operationsOf(rt.config(), rt.workerIndex()), op);
    }

    protected static List<TransactionOutcome> perOperation(WorkerRuntime rt, List<Integer> operations,
                                                           BiFunction<Integer, Integer, TransactionOutcome> op) {
        List<TransactionOutcome> out = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            if (rt.isCancelled()) {
                break;
            }
            out.add(op.apply(operations.get(i), i));
        }
        return out;
    }

    /** 1-based operation numbers of a worker. */
    public static List<Integer> operationsOf(ExperimentConfiguration c, int workerIndex) {
        List<Integer> ops = new ArrayList<>();
        int perWorker = 0;
        for (int g = workerIndex; g < c.transactions(); g += c.threads()) {
            ops.add(++perWorker);
        }
        return ops;
    }

    protected static List<String> committedIds(List<TransactionOutcome> outcomes) {
        return outcomes.stream().filter(o -> o.status() == TxStatus.COMMITTED).map(TransactionOutcome::transactionId).toList();
    }

    protected static long count(List<TransactionOutcome> outcomes, TxStatus status) {
        return outcomes.stream().filter(o -> o.status() == status).count();
    }

    protected static BigDecimal money(Object v) {
        if (v == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal d = v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
        return d.setScale(2, RoundingMode.HALF_UP);
    }

    protected static long asLong(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    protected static Map<String, Object> m(Object... kv) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}

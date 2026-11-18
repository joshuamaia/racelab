package dev.racelab.application.engine;

import dev.racelab.application.engine.ScenarioExceptions.BusinessRuleRejection;
import dev.racelab.application.engine.ScenarioExceptions.ExperimentCancelledException;
import dev.racelab.application.engine.ScenarioExceptions.InjectedFault;
import dev.racelab.application.engine.ScenarioExceptions.IntentionalRollback;
import dev.racelab.application.engine.ScenarioExceptions.LockNotAcquiredException;
import dev.racelab.application.engine.ScenarioExceptions.OptimisticConflictException;
import dev.racelab.domain.model.ErrorType;
import jakarta.persistence.OptimisticLockException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * Maps exceptions to experiment error types using the SQLSTATE reported by PostgreSQL:
 * <ul>
 *   <li>40P01 — deadlock detected</li>
 *   <li>40001 — serialization failure</li>
 *   <li>23505 — unique violation</li>
 *   <li>55P03 — lock not available (lock_timeout)</li>
 *   <li>57014 — query cancelled (statement_timeout or pg_cancel_backend)</li>
 *   <li>25P03 — idle-in-transaction session timeout</li>
 *   <li>08xxx — connection exceptions</li>
 * </ul>
 */
@Component
public class SqlErrorClassifier {

    public Classification classify(Throwable ex, boolean experimentCancelled) {
        if (findCause(ex, ExperimentCancelledException.class) != null) {
            return new Classification(ErrorType.CANCELLED, null, ex.getMessage(), "The experiment was cancelled.", null);
        }
        BusinessRuleRejection rejection = findCause(ex, BusinessRuleRejection.class);
        if (rejection != null) {
            return new Classification(ErrorType.BUSINESS_RULE, null, rejection.getMessage(),
                    "Rejected by business rule: " + rejection.getMessage(), null);
        }
        if (findCause(ex, IntentionalRollback.class) != null) {
            return new Classification(null, null, rootMessage(ex), "Rolled back on purpose by the scenario.", null);
        }
        if (findCause(ex, InjectedFault.class) != null) {
            return new Classification(ErrorType.INJECTED_FAULT, null, rootMessage(ex),
                    "Process crash injected by the experiment.", null);
        }
        LockNotAcquiredException lockNotAcquired = findCause(ex, LockNotAcquiredException.class);
        if (lockNotAcquired != null) {
            return new Classification(ErrorType.LOCK_NOT_ACQUIRED, null, lockNotAcquired.getMessage(),
                    "Lock not acquired: " + lockNotAcquired.getMessage(), null);
        }
        SQLException sql = findSqlException(ex);
        if (sql != null && sql.getSQLState() != null) {
            String state = sql.getSQLState();
            String detail = serverDetail(sql);
            return switch (state) {
                case "40P01" -> new Classification(ErrorType.DEADLOCK, state, sql.getMessage(),
                        "PostgreSQL detected a deadlock and aborted this transaction (it was chosen as the victim).", detail);
                case "40001" -> new Classification(ErrorType.SERIALIZATION_FAILURE, state, sql.getMessage(),
                        "PostgreSQL could not serialize this transaction with a concurrent one; it must be retried.", detail);
                case "23505" -> new Classification(ErrorType.UNIQUE_VIOLATION, state, sql.getMessage(),
                        "A unique constraint rejected a duplicate row.", detail);
                case "55P03" -> new Classification(ErrorType.LOCK_TIMEOUT, state, sql.getMessage(),
                        "The lock was not granted within lock_timeout.", detail);
                case "57014" -> experimentCancelled
                        ? new Classification(ErrorType.CANCELLED, state, sql.getMessage(),
                        "The statement was cancelled because the experiment was cancelled.", detail)
                        : new Classification(ErrorType.TRANSACTION_TIMEOUT, state, sql.getMessage(),
                        "The statement exceeded the transaction timeout.", detail);
                case "25P03" -> new Classification(ErrorType.TRANSACTION_TIMEOUT, state, sql.getMessage(),
                        "The transaction stayed idle for too long and was terminated by PostgreSQL.", detail);
                default -> state.startsWith("08")
                        ? new Classification(ErrorType.CONNECTION_FAILURE, state, sql.getMessage(),
                        "Connection to PostgreSQL failed.", detail)
                        : new Classification(ErrorType.INTERNAL, state, sql.getMessage(),
                        "Unexpected database error (SQLSTATE " + state + ").", detail);
            };
        }
        if (findCause(ex, OptimisticLockingFailureException.class) != null
                || findCause(ex, OptimisticLockException.class) != null
                || findCause(ex, OptimisticConflictException.class) != null) {
            return new Classification(ErrorType.OPTIMISTIC_LOCK, null, rootMessage(ex),
                    "Version conflict: the row changed after it was read (UPDATE … WHERE version = ? affected 0 rows).", null);
        }
        if (findCause(ex, TransactionTimedOutException.class) != null) {
            return new Classification(ErrorType.TRANSACTION_TIMEOUT, null, rootMessage(ex),
                    "The transaction exceeded its timeout.", null);
        }
        if (findCause(ex, CannotCreateTransactionException.class) != null
                || findCause(ex, SQLTransientConnectionException.class) != null) {
            return new Classification(ErrorType.CONNECTION_FAILURE, null, rootMessage(ex),
                    "Could not obtain a database connection.", null);
        }
        return new Classification(ErrorType.INTERNAL, null, rootMessage(ex), "Unexpected application error.", null);
    }

    public static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    static SQLException findSqlException(Throwable ex) {
        Throwable t = ex;
        SQLException found = null;
        while (t != null) {
            if (t instanceof SQLException s) {
                found = s;
                if (s.getSQLState() != null) {
                    return s;
                }
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return found;
    }

    private static String serverDetail(SQLException sql) {
        if (sql instanceof PSQLException p) {
            ServerErrorMessage m = p.getServerErrorMessage();
            return m == null ? null : m.getDetail();
        }
        return null;
    }

    static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        Throwable t = ex;
        while (t != null) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return null;
    }

    /**
     * @param errorType  null for an intentional rollback
     * @param detail     PostgreSQL DETAIL field (for deadlocks it describes the wait-for cycle)
     */
    public record Classification(ErrorType errorType, String sqlState, String message, String friendlyMessage,
                                 String detail) {
    }
}

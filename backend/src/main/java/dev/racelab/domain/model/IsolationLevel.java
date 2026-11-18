package dev.racelab.domain.model;

import org.springframework.transaction.TransactionDefinition;

public enum IsolationLevel {
    READ_UNCOMMITTED(TransactionDefinition.ISOLATION_READ_UNCOMMITTED, "READ UNCOMMITTED"),
    READ_COMMITTED(TransactionDefinition.ISOLATION_READ_COMMITTED, "READ COMMITTED"),
    REPEATABLE_READ(TransactionDefinition.ISOLATION_REPEATABLE_READ, "REPEATABLE READ"),
    SERIALIZABLE(TransactionDefinition.ISOLATION_SERIALIZABLE, "SERIALIZABLE");

    private final int springLevel;
    private final String sqlName;

    IsolationLevel(int springLevel, String sqlName) {
        this.springLevel = springLevel;
        this.sqlName = sqlName;
    }

    public int springLevel() {
        return springLevel;
    }

    public String sqlName() {
        return sqlName;
    }

    /** PostgreSQL silently runs READ UNCOMMITTED as READ COMMITTED. */
    public IsolationLevel effectiveInPostgres() {
        return this == READ_UNCOMMITTED ? READ_COMMITTED : this;
    }
}

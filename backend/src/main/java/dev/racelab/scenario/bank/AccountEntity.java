package dev.racelab.scenario.bank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

// @snippet:start bank-withdrawal OPTIMISTIC_LOCKING java "JPA entity with @Version"
@Entity
@Table(name = "account")
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "experiment_id", nullable = false)
    private String experimentId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Version
    private Long version; // Hibernate adds "AND version = ?" to every UPDATE and increments it

    @Column(name = "update_count", nullable = false)
    private int updateCount;

    public void withdraw(BigDecimal amount) {
        this.balance = this.balance.subtract(amount);
        this.updateCount++;
    }
    // @snippet:end

    protected AccountEntity() {
    }

    public Long getId() {
        return id;
    }

    public String getExperimentId() {
        return experimentId;
    }

    public String getCode() {
        return code;
    }

    public String getOwner() {
        return owner;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Long getVersion() {
        return version;
    }

    public int getUpdateCount() {
        return updateCount;
    }
}

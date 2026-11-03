package com.novabank.transaction.txn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "TRANSACTIONS", schema = "NB_TXN")
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "REFERENCE", nullable = false, unique = true, length = 24)
    private String reference;

    @Column(name = "IDEMPOTENCY_KEY", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "TXN_TYPE", nullable = false, length = 12)
    private TxnType type;

    @Column(name = "FROM_ACCOUNT", length = 12)
    private String fromAccount;

    @Column(name = "TO_ACCOUNT", nullable = false, length = 12)
    private String toAccount;

    @Column(name = "AMOUNT", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "CURRENCY", nullable = false, length = 3)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", nullable = false, length = 10)
    private TxnStatus status = TxnStatus.PENDING;

    @Column(name = "DESCRIPTION", length = 140)
    private String description;

    @Column(name = "FAILURE_REASON", length = 200)
    private String failureReason;

    @Column(name = "INITIATED_BY", nullable = false)
    private Long initiatedBy;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "COMPLETED_AT")
    private Instant completedAt;

    /** Reconciliation claim. Read-only here: only TransactionRepository's conditional updates write it. */
    @Column(name = "RECON_LEASE_UNTIL", insertable = false, updatable = false)
    private Instant reconLeaseUntil;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void complete() {
        this.status = TxnStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail(String reason) {
        this.status = TxnStatus.FAILED;
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 200));
    }

    public Long getId() { return id; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public TxnType getType() { return type; }
    public void setType(TxnType type) { this.type = type; }
    public String getFromAccount() { return fromAccount; }
    public void setFromAccount(String fromAccount) { this.fromAccount = fromAccount; }
    public String getToAccount() { return toAccount; }
    public void setToAccount(String toAccount) { this.toAccount = toAccount; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public TxnStatus getStatus() { return status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getFailureReason() { return failureReason; }
    public Long getInitiatedBy() { return initiatedBy; }
    public void setInitiatedBy(Long initiatedBy) { this.initiatedBy = initiatedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}

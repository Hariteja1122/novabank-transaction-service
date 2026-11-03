package com.novabank.transaction.beneficiary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** A payee a customer saved for quick transfers. Belongs to exactly one customer. */
@Entity
@Table(name = "BENEFICIARIES", schema = "NB_TXN")
public class Beneficiary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "CUSTOMER_ID", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "NICKNAME", nullable = false, length = 60)
    private String nickname;

    /** Fixed once saved: to pay a different account, the customer adds a new payee. */
    @Column(name = "ACCOUNT_NUMBER", nullable = false, updatable = false, length = 12)
    private String accountNumber;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public Instant getCreatedAt() { return createdAt; }
}

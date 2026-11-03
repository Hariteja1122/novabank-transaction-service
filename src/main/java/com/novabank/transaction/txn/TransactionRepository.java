package com.novabank.transaction.txn;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<BankTransaction, Long>,
        JpaSpecificationExecutor<BankTransaction> {

    Optional<BankTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<BankTransaction> findByReference(String reference);

    long countByStatus(TxnStatus status);

    long countByStatusAndCreatedAtAfter(TxnStatus status, Instant after);

    long countByCreatedAtAfter(Instant after);

    // ---- reconciliation (see PendingTransactionReconciler). Every write is conditional on
    // STATUS = 'PENDING', so concurrent instances and the original request cannot overwrite
    // a row that is already settled.

    @Query("""
            select t from BankTransaction t
            where t.status = com.novabank.transaction.txn.TxnStatus.PENDING
              and t.createdAt < :cutoff
              and (t.reconLeaseUntil is null or t.reconLeaseUntil < :now)
            order by t.createdAt""")
    List<BankTransaction> findReconcilable(@Param("cutoff") Instant cutoff, @Param("now") Instant now, Pageable page);

    /** 1 if this caller now owns the row until {@code until}, 0 if it is settled or another instance holds it. */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE NB_TXN.TRANSACTIONS SET RECON_LEASE_UNTIL = :until
            WHERE ID = :id AND STATUS = 'PENDING'
              AND (RECON_LEASE_UNTIL IS NULL OR RECON_LEASE_UNTIL < :now)""")
    int claimForReconciliation(@Param("id") Long id, @Param("until") Instant until, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE NB_TXN.TRANSACTIONS SET STATUS = 'COMPLETED', COMPLETED_AT = :completedAt, RECON_LEASE_UNTIL = NULL
            WHERE ID = :id AND STATUS = 'PENDING'""")
    int completePending(@Param("id") Long id, @Param("completedAt") Instant completedAt);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE NB_TXN.TRANSACTIONS SET STATUS = 'FAILED', FAILURE_REASON = :reason, RECON_LEASE_UNTIL = NULL
            WHERE ID = :id AND STATUS = 'PENDING'""")
    int failPending(@Param("id") Long id, @Param("reason") String reason);
}

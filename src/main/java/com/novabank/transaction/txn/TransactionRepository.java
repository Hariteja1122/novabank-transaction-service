package com.novabank.transaction.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<BankTransaction, Long>,
        JpaSpecificationExecutor<BankTransaction> {

    Optional<BankTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<BankTransaction> findByReference(String reference);

    long countByStatus(TxnStatus status);

    long countByStatusAndCreatedAtAfter(TxnStatus status, Instant after);

    long countByCreatedAtAfter(Instant after);
}

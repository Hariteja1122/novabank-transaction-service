package com.novabank.transaction.txn;

import com.novabank.transaction.audit.Audit;
import com.novabank.transaction.client.AccountClient;
import com.novabank.transaction.client.AccountClient.AccountRejectedException;
import com.novabank.transaction.common.ApiException;
import com.novabank.transaction.txn.TransactionDtos.DailyVolume;
import com.novabank.transaction.txn.TransactionDtos.DepositRequest;
import com.novabank.transaction.txn.TransactionDtos.TransactionDto;
import com.novabank.transaction.txn.TransactionDtos.TransactionSummary;
import com.novabank.transaction.txn.TransactionDtos.TransferRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates money movement. Each transfer is recorded as PENDING first, then the
 * balance change runs inside account-service, then the record becomes COMPLETED or
 * FAILED. The Idempotency-Key makes retries from the UI or Postman safe: the same key
 * always returns the original transaction instead of moving money twice.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository repo;
    private final AccountClient accounts;
    private final ReferenceGenerator references;
    private final JdbcTemplate jdbc;
    private final BigDecimal maxTransferAmount;

    public TransactionService(TransactionRepository repo, AccountClient accounts, ReferenceGenerator references,
                              JdbcTemplate jdbc,
                              @Value("${novabank.limits.max-transfer-amount}") BigDecimal maxTransferAmount) {
        this.repo = repo;
        this.accounts = accounts;
        this.references = references;
        this.jdbc = jdbc;
        this.maxTransferAmount = maxTransferAmount;
    }

    public TransactionDto transfer(TransferRequest req, Long customerId, String idempotencyKey) {
        Audit.detail("fromAccount", req.fromAccount());
        Audit.detail("toAccount", req.toAccount());
        Audit.detail("amount", req.amount().toPlainString());
        String key = normalizeKey(idempotencyKey);
        var existing = repo.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            log.info("Idempotent replay of {} for key {}", existing.get().getReference(), key);
            Audit.detail("replayed", true);
            return replay(existing.get());
        }
        if (req.fromAccount().equals(req.toAccount())) {
            throw ApiException.badRequest("Source and destination accounts must be different");
        }
        if (req.amount().compareTo(maxTransferAmount) > 0) {
            throw ApiException.badRequest("Online transfers are limited to $" + maxTransferAmount.toPlainString()
                    + " per transaction");
        }

        BankTransaction txn = new BankTransaction();
        txn.setReference(references.next());
        txn.setIdempotencyKey(key);
        txn.setType(TxnType.TRANSFER);
        txn.setFromAccount(req.fromAccount());
        txn.setToAccount(req.toAccount());
        txn.setAmount(req.amount());
        txn.setDescription(StringUtils.hasText(req.description()) ? req.description().trim() : "Funds transfer");
        txn.setInitiatedBy(customerId);
        txn = savePending(txn);
        Audit.target("TRANSACTION", txn.getReference());

        try {
            accounts.transfer(req.fromAccount(), req.toAccount(), req.amount(), customerId, txn.getReference());
            txn.complete();
        } catch (AccountRejectedException ex) {
            txn.fail(ex.getMessage());
        }
        txn = repo.save(txn);
        return replay(txn);
    }

    public TransactionDto deposit(DepositRequest req, Long adminId, String idempotencyKey) {
        Audit.detail("toAccount", req.toAccount());
        Audit.detail("amount", req.amount().toPlainString());
        String key = normalizeKey(idempotencyKey);
        var existing = repo.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            Audit.detail("replayed", true);
            return replay(existing.get());
        }
        BankTransaction txn = new BankTransaction();
        txn.setReference(references.next());
        txn.setIdempotencyKey(key);
        txn.setType(TxnType.DEPOSIT);
        txn.setToAccount(req.toAccount());
        txn.setAmount(req.amount());
        txn.setDescription(StringUtils.hasText(req.description()) ? req.description().trim() : "Branch deposit");
        txn.setInitiatedBy(adminId);
        txn = savePending(txn);
        Audit.target("TRANSACTION", txn.getReference());

        try {
            accounts.deposit(req.toAccount(), req.amount(), txn.getReference());
            txn.complete();
        } catch (AccountRejectedException ex) {
            txn.fail(ex.getMessage());
        }
        return replay(repo.save(txn));
    }

    public Page<TransactionDto> historyFor(Long customerId, String accountFilter, Pageable pageable) {
        Set<String> own = accounts.accountNumbersOf(customerId);
        if (own.isEmpty()) {
            return Page.empty(pageable);
        }
        Set<String> scope = own;
        if (StringUtils.hasText(accountFilter)) {
            if (!own.contains(accountFilter)) {
                throw ApiException.notFound("Account not found");
            }
            scope = Set.of(accountFilter);
        }
        return repo.findAll(TransactionSpecs.touching(scope), pageable).map(t -> TransactionDto.from(t, own));
    }

    public TransactionDto byReference(String reference, Long customerId, boolean admin) {
        BankTransaction txn = repo.findByReference(reference)
                .orElseThrow(() -> ApiException.notFound("Transaction not found"));
        if (admin) {
            return TransactionDto.from(txn);
        }
        Set<String> own = accounts.accountNumbersOf(customerId);
        boolean visible = own.contains(txn.getToAccount())
                || (txn.getFromAccount() != null && own.contains(txn.getFromAccount()));
        if (!visible) {
            throw ApiException.notFound("Transaction not found");
        }
        return TransactionDto.from(txn, own);
    }

    public TransactionSummary summary() {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        BigDecimal volumeToday = jdbc.queryForObject(
                "SELECT COALESCE(SUM(AMOUNT), 0) FROM NB_TXN.TRANSACTIONS WHERE STATUS = 'COMPLETED' AND CREATED_AT >= CURRENT DATE",
                BigDecimal.class);
        List<DailyVolume> days = jdbc.query(
                "SELECT TXN_DATE, TXN_COUNT, TOTAL_AMOUNT FROM NB_TXN.V_DAILY_VOLUME "
                        + "WHERE TXN_DATE >= CURRENT DATE - 13 DAYS ORDER BY TXN_DATE",
                (rs, i) -> new DailyVolume(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getBigDecimal(3)));
        return new TransactionSummary(
                repo.countByCreatedAtAfter(startOfDay),
                volumeToday,
                repo.countByStatusAndCreatedAtAfter(TxnStatus.FAILED, Instant.now().minus(Duration.ofDays(7))),
                repo.countByStatus(TxnStatus.PENDING),
                days);
    }

    private BankTransaction savePending(BankTransaction txn) {
        try {
            return repo.saveAndFlush(txn);
        } catch (DataIntegrityViolationException ex) {
            // Two identical requests raced each other; the unique key on IDEMPOTENCY_KEY picked a winner.
            throw new ApiException(HttpStatus.CONFLICT, "This request is already being processed");
        }
    }

    private TransactionDto replay(BankTransaction txn) {
        Audit.target("TRANSACTION", txn.getReference());
        Audit.detail("status", txn.getStatus());
        if (txn.getStatus() == TxnStatus.FAILED) {
            throw new TransferFailedException(txn.getReference(), txn.getFailureReason());
        }
        return TransactionDto.from(txn);
    }

    private static String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return UUID.randomUUID().toString();
        }
        String trimmed = key.trim();
        if (trimmed.length() > 64) {
            throw ApiException.badRequest("Idempotency-Key must be at most 64 characters");
        }
        return trimmed;
    }
}

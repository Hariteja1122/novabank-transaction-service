package com.novabank.transaction.reconciliation;

import com.novabank.transaction.audit.AuditLog;
import com.novabank.transaction.audit.AuditWriter;
import com.novabank.transaction.client.AccountClient;
import com.novabank.transaction.client.AccountClient.AccountRejectedException;
import com.novabank.transaction.client.AccountClient.LedgerEntry;
import com.novabank.transaction.client.AccountClient.LedgerOutcome;
import com.novabank.transaction.common.ApiException;
import com.novabank.transaction.txn.BankTransaction;
import com.novabank.transaction.txn.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Settles transfers and deposits left PENDING because account-service could not be reached
 * or the request died midway. For each, account-service gives the final word: APPLIED
 * (the money moved, so COMPLETED) or VOIDED (it did not, and now never will, so FAILED).
 *
 * <p>Safe with any number of instances: a row is first claimed with a conditional UPDATE
 * (one winner per lease), the outcome call is idempotent, and the final UPDATE only
 * applies while the row is still PENDING. Two instances working the same row would reach
 * the same answer and only the first write lands.
 */
@Component
public class PendingTransactionReconciler {

    private static final Logger log = LoggerFactory.getLogger(PendingTransactionReconciler.class);
    static final String VOIDED_REASON = "Not applied by account-service; cancelled by reconciliation";

    private final TransactionRepository repo;
    private final AccountClient accounts;
    private final ReconciliationProperties props;
    private final AuditWriter audit;

    public PendingTransactionReconciler(TransactionRepository repo, AccountClient accounts, ReconciliationProperties props,
                                        AuditWriter audit) {
        this.repo = repo;
        this.accounts = accounts;
        this.props = props;
        this.audit = audit;
    }

    public record RunResult(int completed, int failed, int deferred) {
    }

    @Scheduled(fixedDelayString = "${novabank.reconciliation.interval:PT30S}",
            initialDelayString = "${novabank.reconciliation.initial-delay:PT30S}")
    void scheduledRun() {
        if (props.enabled()) {
            runOnce();
        }
    }

    public RunResult runOnce() {
        Instant now = Instant.now();
        var due = repo.findReconcilable(now.minus(props.pendingAge()), now, PageRequest.of(0, props.batchSize()));
        int completed = 0;
        int failed = 0;
        int deferred = 0;
        for (BankTransaction txn : due) {
            if (repo.claimForReconciliation(txn.getId(), Instant.now().plus(props.lease()), Instant.now()) == 0) {
                continue; // settled meanwhile, or another instance owns it
            }
            LedgerEntry entry;
            try {
                entry = accounts.voidUnlessApplied(txn.getReference());
            } catch (ApiException ex) {
                if (ex.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
                    // No point calling again for every row. The lease runs out and a later run retries.
                    log.warn("Reconciliation paused: account-service unavailable ({})", ex.getMessage());
                    deferred++;
                    break;
                }
                throw ex;
            } catch (AccountRejectedException ex) {
                // 409: a request for this reference is still in flight. Try again after the lease.
                log.info("Reconciliation of {} deferred: {}", txn.getReference(), ex.getMessage());
                deferred++;
                continue;
            }
            if (entry.outcome() == LedgerOutcome.APPLIED) {
                int settled = repo.completePending(txn.getId(), entry.recordedAt());
                completed += settled;
                if (settled > 0) {
                    record(txn, "COMPLETED");
                }
                log.info("Reconciled {} as COMPLETED (account-service applied it at {})", txn.getReference(), entry.recordedAt());
            } else {
                int settled = repo.failPending(txn.getId(), VOIDED_REASON);
                failed += settled;
                if (settled > 0) {
                    record(txn, "FAILED");
                }
                log.warn("Reconciled {} as FAILED: account-service never applied it", txn.getReference());
            }
        }
        if (completed + failed + deferred > 0) {
            log.info("Reconciliation run: {} completed, {} failed, {} deferred", completed, failed, deferred);
        }
        return new RunResult(completed, failed, deferred);
    }

    /** Only rows this instance actually settled are audited, so each settlement appears once. */
    private void record(BankTransaction txn, String result) {
        AuditLog entry = new AuditLog();
        entry.setOccurredAt(Instant.now());
        entry.setActorRole("SYSTEM");
        entry.setAction("RECONCILE");
        entry.setTargetType("TRANSACTION");
        entry.setTargetId(txn.getReference());
        entry.setOutcome(AuditLog.Outcome.SUCCESS);
        entry.setDetails("{\"result\":\"" + result + "\"}");
        try {
            audit.write(entry);
        } catch (RuntimeException ex) {
            log.error("AUDIT WRITE FAILED action=RECONCILE target={} result={}", txn.getReference(), result, ex);
        }
    }
}

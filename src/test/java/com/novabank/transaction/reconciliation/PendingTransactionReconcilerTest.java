package com.novabank.transaction.reconciliation;

import com.novabank.transaction.client.AccountClient;
import com.novabank.transaction.client.AccountClient.AccountRejectedException;
import com.novabank.transaction.client.AccountClient.LedgerEntry;
import com.novabank.transaction.client.AccountClient.LedgerOutcome;
import com.novabank.transaction.common.ApiException;
import com.novabank.transaction.txn.BankTransaction;
import com.novabank.transaction.txn.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingTransactionReconcilerTest {

    private final TransactionRepository repo = mock(TransactionRepository.class);
    private final AccountClient accounts = mock(AccountClient.class);
    private final PendingTransactionReconciler reconciler = new PendingTransactionReconciler(repo, accounts,
            new ReconciliationProperties(true, Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(2), 50));

    @BeforeEach
    void claimsSucceedByDefault() {
        when(repo.claimForReconciliation(anyLong(), any(), any())).thenReturn(1);
        when(repo.completePending(anyLong(), any())).thenReturn(1);
        when(repo.failPending(anyLong(), anyString())).thenReturn(1);
    }

    private BankTransaction pending(long id, String reference) {
        BankTransaction t = mock(BankTransaction.class);
        when(t.getId()).thenReturn(id);
        when(t.getReference()).thenReturn(reference);
        return t;
    }

    private void due(BankTransaction... rows) {
        when(repo.findReconcilable(any(), any(), any())).thenReturn(List.of(rows));
    }

    @Test
    void appliedReferenceBecomesCompletedAtTheTimeTheMoneyMoved() {
        due(pending(1, "NB1"));
        Instant applied = Instant.parse("2026-11-02T10:00:00Z");
        when(accounts.voidUnlessApplied("NB1")).thenReturn(new LedgerEntry("NB1", LedgerOutcome.APPLIED, applied));

        var result = reconciler.runOnce();

        assertThat(result.completed()).isEqualTo(1);
        verify(repo).completePending(1L, applied);
        verify(repo, never()).failPending(anyLong(), anyString());
    }

    @Test
    void voidedReferenceBecomesFailed() {
        due(pending(2, "NB2"));
        when(accounts.voidUnlessApplied("NB2")).thenReturn(new LedgerEntry("NB2", LedgerOutcome.VOIDED, Instant.now()));

        var result = reconciler.runOnce();

        assertThat(result.failed()).isEqualTo(1);
        verify(repo).failPending(2L, PendingTransactionReconciler.VOIDED_REASON);
    }

    @Test
    void rowClaimedByAnotherInstanceIsLeftAlone() {
        due(pending(3, "NB3"));
        when(repo.claimForReconciliation(eq(3L), any(), any())).thenReturn(0);

        reconciler.runOnce();

        verify(accounts, never()).voidUnlessApplied(anyString());
    }

    @Test
    void accountServiceDownStopsTheRunInsteadOfHammeringIt() {
        due(pending(4, "NB4"), pending(5, "NB5"));
        when(accounts.voidUnlessApplied("NB4"))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Account service is unavailable"));

        var result = reconciler.runOnce();

        assertThat(result.deferred()).isEqualTo(1);
        verify(repo, never()).claimForReconciliation(eq(5L), any(), any());
        verify(repo, never()).completePending(anyLong(), any());
        verify(repo, never()).failPending(anyLong(), anyString());
    }

    @Test
    void referenceStillInFlightIsDeferredAndTheRunContinues() {
        due(pending(6, "NB6"), pending(7, "NB7"));
        when(accounts.voidUnlessApplied("NB6")).thenThrow(new AccountRejectedException(409, "Reference NB6 was already settled"));
        when(accounts.voidUnlessApplied("NB7")).thenReturn(new LedgerEntry("NB7", LedgerOutcome.VOIDED, Instant.now()));

        var result = reconciler.runOnce();

        assertThat(result).isEqualTo(new PendingTransactionReconciler.RunResult(0, 1, 1));
        verify(repo, never()).failPending(eq(6L), anyString());
    }
}

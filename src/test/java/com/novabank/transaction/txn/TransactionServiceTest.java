package com.novabank.transaction.txn;

import com.novabank.transaction.client.AccountClient;
import com.novabank.transaction.client.AccountClient.AccountRejectedException;
import com.novabank.transaction.common.ApiException;
import com.novabank.transaction.txn.TransactionDtos.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionServiceTest {

    private final TransactionRepository repo = mock(TransactionRepository.class);
    private final AccountClient accounts = mock(AccountClient.class);
    private TransactionService service;

    @BeforeEach
    void setUp() {
        service = new TransactionService(repo, accounts, new ReferenceGenerator(), mock(JdbcTemplate.class),
                new BigDecimal("10000.00"));
        when(repo.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private TransferRequest req(String amount) {
        return new TransferRequest("100245180031", "100245180057", new BigDecimal(amount), "Rent");
    }

    @Test
    void successfulTransferIsCompleted() {
        var dto = service.transfer(req("250.00"), 3L, "key-1");

        assertThat(dto.status()).isEqualTo(TxnStatus.COMPLETED);
        assertThat(dto.reference()).startsWith("NB");
        verify(accounts).transfer("100245180031", "100245180057", new BigDecimal("250.00"), 3L, dto.reference());
    }

    @Test
    void sameIdempotencyKeyNeverMovesMoneyTwice() {
        BankTransaction earlier = new BankTransaction();
        earlier.setReference("NB261102-ABCDEF");
        earlier.complete();
        when(repo.findByIdempotencyKey("key-2")).thenReturn(Optional.of(earlier));

        var dto = service.transfer(req("250.00"), 3L, "key-2");

        assertThat(dto.reference()).isEqualTo("NB261102-ABCDEF");
        verify(accounts, never()).transfer(any(), any(), any(), any(), any());
    }

    @Test
    void rejectedTransferIsRecordedAsFailed() {
        doThrow(new AccountRejectedException(422, "Insufficient funds"))
                .when(accounts).transfer(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.transfer(req("250.00"), 3L, "key-3"))
                .isInstanceOf(TransferFailedException.class)
                .hasMessage("Insufficient funds");
        verify(repo).save(org.mockito.ArgumentMatchers.<BankTransaction>argThat(t -> t.getStatus() == TxnStatus.FAILED));
    }

    @Test
    void transferLimitIsEnforced() {
        assertThatThrownBy(() -> service.transfer(req("10000.01"), 3L, "key-4"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("limited");
        verify(accounts, never()).transfer(any(), any(), any(), any(), any());
    }
}

package com.novabank.transaction.beneficiary;

import com.novabank.transaction.beneficiary.BeneficiaryDtos.CreateBeneficiaryRequest;
import com.novabank.transaction.client.AccountClient;
import com.novabank.transaction.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BeneficiaryServiceTest {

    private static final long JOHN = 3L;
    private static final String PRIYA = "100245180057";

    private final BeneficiaryRepository repo = mock(BeneficiaryRepository.class);
    private final AccountClient accounts = mock(AccountClient.class);
    private final BeneficiaryService service = new BeneficiaryService(repo, accounts);

    @BeforeEach
    void setUp() {
        when(accounts.accountNumbersOf(JOHN)).thenReturn(Set.of("100245180031", "100245180049"));
        when(accounts.exists(PRIYA)).thenReturn(true);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void savesPayeeWithTrimmedNickname() {
        var dto = service.create(new CreateBeneficiaryRequest("  Priya (rent) ", PRIYA), JOHN);

        assertThat(dto.nickname()).isEqualTo("Priya (rent)");
        assertThat(dto.accountNumber()).isEqualTo(PRIYA);
        verify(repo).saveAndFlush(any());
    }

    @Test
    void ownAccountCannotBeAPayee() {
        assertThatThrownBy(() -> service.create(new CreateBeneficiaryRequest("Me", "100245180049"), JOHN))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("your own accounts");
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void unknownAccountIsRejected() {
        when(accounts.exists("100245189999")).thenReturn(false);

        assertThatThrownBy(() -> service.create(new CreateBeneficiaryRequest("Typo", "100245189999"), JOHN))
                .hasMessage("No NovaBank account has that number");
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void duplicatePayeeIsAConflict() {
        when(repo.existsByCustomerIdAndAccountNumber(JOHN, PRIYA)).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateBeneficiaryRequest("Priya again", PRIYA), JOHN))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(accounts, never()).exists(any());
    }

    @Test
    void racingDuplicateIsAConflictNotAServerError() {
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("UQ_BENEFICIARY_ACCOUNT"));

        assertThatThrownBy(() -> service.create(new CreateBeneficiaryRequest("Priya", PRIYA), JOHN))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void payeeLimitIsEnforced() {
        when(repo.countByCustomerId(JOHN)).thenReturn((long) BeneficiaryService.MAX_PER_CUSTOMER);

        assertThatThrownBy(() -> service.create(new CreateBeneficiaryRequest("One too many", PRIYA), JOHN))
                .hasMessageContaining("up to 50 payees");
    }

    @Test
    void anotherCustomersPayeeLooksLikeItDoesNotExist() {
        when(repo.findByIdAndCustomerId(7L, JOHN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(7L, JOHN))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.rename(7L, "Mine now", JOHN))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(repo, never()).delete(any());
    }
}

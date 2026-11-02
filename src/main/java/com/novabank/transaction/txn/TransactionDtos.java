package com.novabank.transaction.txn;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public final class TransactionDtos {

    private TransactionDtos() {
    }

    public record TransferRequest(
            @NotBlank @Pattern(regexp = "\\d{12}", message = "Account numbers are 12 digits") String fromAccount,
            @NotBlank @Pattern(regexp = "\\d{12}", message = "Account numbers are 12 digits") String toAccount,
            @NotNull @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
            @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Size(max = 140) String description) {
    }

    public record DepositRequest(
            @NotBlank @Pattern(regexp = "\\d{12}", message = "Account numbers are 12 digits") String toAccount,
            @NotNull @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
            @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Size(max = 140) String description) {
    }

    /** DEBIT / CREDIT is relative to the caller's own accounts; INTERNAL means between two of them. */
    public enum Direction { DEBIT, CREDIT, INTERNAL }

    public record TransactionDto(Long id, String reference, TxnType type, String fromAccount, String toAccount,
                                 BigDecimal amount, String currency, TxnStatus status, String description,
                                 String failureReason, Long initiatedBy, Instant createdAt, Instant completedAt,
                                 Direction direction) {

        public static TransactionDto from(BankTransaction t) {
            return from(t, null);
        }

        public static TransactionDto from(BankTransaction t, Set<String> ownAccounts) {
            Direction direction = null;
            if (ownAccounts != null) {
                boolean out = t.getFromAccount() != null && ownAccounts.contains(t.getFromAccount());
                boolean in = ownAccounts.contains(t.getToAccount());
                direction = out && in ? Direction.INTERNAL : out ? Direction.DEBIT : Direction.CREDIT;
            }
            return new TransactionDto(t.getId(), t.getReference(), t.getType(), t.getFromAccount(), t.getToAccount(),
                    t.getAmount(), t.getCurrency(), t.getStatus(), t.getDescription(), t.getFailureReason(),
                    t.getInitiatedBy(), t.getCreatedAt(), t.getCompletedAt(), direction);
        }
    }

    public record DailyVolume(LocalDate date, long count, BigDecimal amount) {
    }

    public record TransactionSummary(long transactionsToday, BigDecimal volumeToday, long failedLast7Days,
                                     long pending, List<DailyVolume> last14Days) {
    }
}

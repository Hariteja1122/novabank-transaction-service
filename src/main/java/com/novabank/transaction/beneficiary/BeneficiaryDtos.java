package com.novabank.transaction.beneficiary;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class BeneficiaryDtos {

    private BeneficiaryDtos() {
    }

    public record BeneficiaryDto(Long id, String nickname, String accountNumber, Instant createdAt) {

        public static BeneficiaryDto from(Beneficiary b) {
            return new BeneficiaryDto(b.getId(), b.getNickname(), b.getAccountNumber(), b.getCreatedAt());
        }
    }

    public record CreateBeneficiaryRequest(
            @NotBlank(message = "Enter a name for this payee") @Size(max = 60) String nickname,
            @NotBlank @Pattern(regexp = "\\d{12}", message = "Account numbers are 12 digits") String accountNumber) {
    }

    public record RenameBeneficiaryRequest(
            @NotBlank(message = "Enter a name for this payee") @Size(max = 60) String nickname) {
    }
}

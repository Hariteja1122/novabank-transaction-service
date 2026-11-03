package com.novabank.transaction.beneficiary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long> {

    List<Beneficiary> findByCustomerIdOrderByNicknameAsc(Long customerId);

    Optional<Beneficiary> findByIdAndCustomerId(Long id, Long customerId);

    boolean existsByCustomerIdAndAccountNumber(Long customerId, String accountNumber);

    long countByCustomerId(Long customerId);
}

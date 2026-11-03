package com.novabank.transaction.beneficiary;

import com.novabank.transaction.audit.Audit;
import com.novabank.transaction.beneficiary.BeneficiaryDtos.BeneficiaryDto;
import com.novabank.transaction.beneficiary.BeneficiaryDtos.CreateBeneficiaryRequest;
import com.novabank.transaction.client.AccountClient;
import com.novabank.transaction.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Saved payees. Every operation is scoped to the calling customer: another customer's
 * payee id answers 404, the same as an id that does not exist.
 */
@Service
public class BeneficiaryService {

    private static final Logger log = LoggerFactory.getLogger(BeneficiaryService.class);
    static final int MAX_PER_CUSTOMER = 50;

    private final BeneficiaryRepository repo;
    private final AccountClient accounts;

    public BeneficiaryService(BeneficiaryRepository repo, AccountClient accounts) {
        this.repo = repo;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<BeneficiaryDto> list(Long customerId) {
        return repo.findByCustomerIdOrderByNicknameAsc(customerId).stream().map(BeneficiaryDto::from).toList();
    }

    @Transactional(readOnly = true)
    public BeneficiaryDto get(Long id, Long customerId) {
        return BeneficiaryDto.from(find(id, customerId));
    }

    public BeneficiaryDto create(CreateBeneficiaryRequest req, Long customerId) {
        String number = req.accountNumber();
        Audit.detail("accountNumber", number);
        if (repo.existsByCustomerIdAndAccountNumber(customerId, number)) {
            throw ApiException.conflict("You already have a payee for this account");
        }
        if (repo.countByCustomerId(customerId) >= MAX_PER_CUSTOMER) {
            throw ApiException.badRequest("You can save up to " + MAX_PER_CUSTOMER + " payees. Remove one to add another.");
        }
        if (accounts.accountNumbersOf(customerId).contains(number)) {
            throw ApiException.badRequest("This is one of your own accounts. Pick it under My accounts when you move money.");
        }
        if (!accounts.exists(number)) {
            throw ApiException.badRequest("No NovaBank account has that number");
        }

        Beneficiary b = new Beneficiary();
        b.setCustomerId(customerId);
        b.setNickname(req.nickname().trim());
        b.setAccountNumber(number);
        try {
            b = repo.saveAndFlush(b);
        } catch (DataIntegrityViolationException ex) {
            // Two identical requests raced; UQ_BENEFICIARY_ACCOUNT kept only one.
            throw ApiException.conflict("You already have a payee for this account");
        }
        Audit.target("BENEFICIARY", b.getId());
        log.info("Customer {} saved payee {} for account {}", customerId, b.getId(), number);
        return BeneficiaryDto.from(b);
    }

    @Transactional
    public BeneficiaryDto rename(Long id, String nickname, Long customerId) {
        Beneficiary b = find(id, customerId);
        b.setNickname(nickname.trim());
        return BeneficiaryDto.from(b);
    }

    @Transactional
    public void delete(Long id, Long customerId) {
        Beneficiary b = find(id, customerId);
        Audit.detail("accountNumber", b.getAccountNumber());
        repo.delete(b);
        log.info("Customer {} removed payee {}", customerId, id);
    }

    private Beneficiary find(Long id, Long customerId) {
        return repo.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> ApiException.notFound("Payee not found"));
    }
}

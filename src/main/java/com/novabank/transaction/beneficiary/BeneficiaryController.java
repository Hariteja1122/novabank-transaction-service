package com.novabank.transaction.beneficiary;

import com.novabank.transaction.audit.Audited;
import com.novabank.transaction.beneficiary.BeneficiaryDtos.BeneficiaryDto;
import com.novabank.transaction.beneficiary.BeneficiaryDtos.CreateBeneficiaryRequest;
import com.novabank.transaction.beneficiary.BeneficiaryDtos.RenameBeneficiaryRequest;
import com.novabank.transaction.common.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The caller's saved payees. Routed by the gateway from /api/beneficiaries/**. */
@RestController
@RequestMapping("/api/beneficiaries")
public class BeneficiaryController {

    private final BeneficiaryService service;

    public BeneficiaryController(BeneficiaryService service) {
        this.service = service;
    }

    @GetMapping
    public List<BeneficiaryDto> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(CurrentUser.id(jwt));
    }

    @GetMapping("/{id}")
    public BeneficiaryDto get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return service.get(id, CurrentUser.id(jwt));
    }

    @PostMapping
    @Audited(action = "BENEFICIARY_ADD", targetType = "BENEFICIARY")
    @ResponseStatus(HttpStatus.CREATED)
    public BeneficiaryDto create(@Valid @RequestBody CreateBeneficiaryRequest req, @AuthenticationPrincipal Jwt jwt) {
        return service.create(req, CurrentUser.id(jwt));
    }

    /** Only the nickname can change. A different account number is a different payee. */
    @PutMapping("/{id}")
    @Audited(action = "BENEFICIARY_RENAME", targetType = "BENEFICIARY", targetParam = "id")
    public BeneficiaryDto rename(@PathVariable Long id, @Valid @RequestBody RenameBeneficiaryRequest req,
                                 @AuthenticationPrincipal Jwt jwt) {
        return service.rename(id, req.nickname(), CurrentUser.id(jwt));
    }

    @DeleteMapping("/{id}")
    @Audited(action = "BENEFICIARY_DELETE", targetType = "BENEFICIARY", targetParam = "id")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, CurrentUser.id(jwt));
    }
}

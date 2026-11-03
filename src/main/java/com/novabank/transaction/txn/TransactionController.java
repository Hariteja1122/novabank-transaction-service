package com.novabank.transaction.txn;

import com.novabank.transaction.audit.Audited;
import com.novabank.transaction.common.CurrentUser;
import com.novabank.transaction.txn.TransactionDtos.TransactionDto;
import com.novabank.transaction.txn.TransactionDtos.TransferRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping("/transfer")
    @Audited(action = "TRANSFER", targetType = "TRANSACTION")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionDto transfer(@Valid @RequestBody TransferRequest req,
                                   @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                   @AuthenticationPrincipal Jwt jwt) {
        return service.transfer(req, CurrentUser.id(jwt), idempotencyKey);
    }

    @GetMapping
    public Page<TransactionDto> history(@RequestParam(required = false) String account,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @AuthenticationPrincipal Jwt jwt) {
        var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        return service.historyFor(CurrentUser.id(jwt), account, pageable);
    }

    @GetMapping("/{reference}")
    public TransactionDto get(@PathVariable String reference, @AuthenticationPrincipal Jwt jwt) {
        return service.byReference(reference, CurrentUser.id(jwt), CurrentUser.isAdmin(jwt));
    }
}

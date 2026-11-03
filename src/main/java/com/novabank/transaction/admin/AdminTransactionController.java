package com.novabank.transaction.admin;

import com.novabank.transaction.audit.Audited;
import com.novabank.transaction.common.CurrentUser;
import com.novabank.transaction.txn.TransactionDtos.DepositRequest;
import com.novabank.transaction.txn.TransactionDtos.TransactionDto;
import com.novabank.transaction.txn.TransactionDtos.TransactionSummary;
import com.novabank.transaction.txn.TransactionRepository;
import com.novabank.transaction.txn.TransactionService;
import com.novabank.transaction.txn.TransactionSpecs;
import com.novabank.transaction.txn.TxnStatus;
import com.novabank.transaction.txn.TxnType;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Transaction monitoring and teller style deposits for operations staff. */
@RestController
@RequestMapping("/api/admin/transactions")
public class AdminTransactionController {

    private final TransactionService service;
    private final TransactionRepository repo;

    public AdminTransactionController(TransactionService service, TransactionRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    @GetMapping
    public Page<TransactionDto> search(@RequestParam(required = false) TxnStatus status,
                                       @RequestParam(required = false) TxnType type,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        return repo.findAll(TransactionSpecs.filter(status, type, q), pageable).map(TransactionDto::from);
    }

    @GetMapping("/summary")
    public TransactionSummary summary() {
        return service.summary();
    }

    @PostMapping("/deposit")
    @Audited(action = "DEPOSIT", targetType = "TRANSACTION")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionDto deposit(@Valid @RequestBody DepositRequest req,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                  @AuthenticationPrincipal Jwt jwt) {
        return service.deposit(req, CurrentUser.id(jwt), idempotencyKey);
    }
}

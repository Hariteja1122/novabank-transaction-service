package com.novabank.transaction.txn;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class TransactionSpecs {

    private TransactionSpecs() {
    }

    /** Transactions where any of the given accounts is the sender or the receiver. */
    public static Specification<BankTransaction> touching(Collection<String> accounts) {
        return (root, query, cb) -> cb.or(root.get("fromAccount").in(accounts), root.get("toAccount").in(accounts));
    }

    public static Specification<BankTransaction> filter(TxnStatus status, TxnType type, String q) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (type != null) {
                p.add(cb.equal(root.get("type"), type));
            }
            if (StringUtils.hasText(q)) {
                String like = "%" + q.trim().toLowerCase() + "%";
                p.add(cb.or(
                        cb.like(cb.lower(root.<String>get("reference")), like),
                        cb.like(root.<String>get("fromAccount"), like),
                        cb.like(root.<String>get("toAccount"), like),
                        cb.like(cb.lower(root.<String>get("description")), like)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }
}

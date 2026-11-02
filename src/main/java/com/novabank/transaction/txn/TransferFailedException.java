package com.novabank.transaction.txn;

/** A recorded transaction that account-service refused, e.g. insufficient funds. Maps to HTTP 422. */
public class TransferFailedException extends RuntimeException {

    private final String reference;

    public TransferFailedException(String reference, String reason) {
        super(reason == null ? "Transaction failed" : reason);
        this.reference = reference;
    }

    public String getReference() {
        return reference;
    }
}

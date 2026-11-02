# novabank-transaction-service

Fund transfers, teller deposits, transaction history and monitoring.

| | |
|---|---|
| Port | 8083 (Cloud Run: 8080 via `PORT`) |
| Db2 schema | `NB_TXN` (table `TRANSACTIONS`, view `V_DAILY_VOLUME`) |
| Calls | account-service `/internal/accounts/**` |
| Swagger (local/dev) | http://localhost:8083/swagger-ui.html |

## Transfer flow

1. Look up the `Idempotency-Key` header. If this key was seen before, return the original
   result. Retrying a request can never move money twice.
2. Validate business rules (different accounts, $10,000 per transfer limit).
3. Insert the row as `PENDING` (a unique index on `IDEMPOTENCY_KEY` resolves races).
4. Ask account-service to debit and credit atomically.
5. Mark the row `COMPLETED`, or `FAILED` with the reason (insufficient funds, frozen account,
   not your account). Failures return HTTP 422 with the transaction reference.

If account-service is unreachable the row stays `PENDING` and the admin dashboard counts it.
A reconciliation job that resolves stuck rows is a good next feature.

## Endpoints

| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/api/transactions/transfer` | customer | Transfer from an own account. Header `Idempotency-Key` |
| GET | `/api/transactions?account=&page=&size=` | customer | My history, each row tagged DEBIT, CREDIT or INTERNAL |
| GET | `/api/transactions/{reference}` | owner or ADMIN | Receipt |
| GET | `/api/admin/transactions?status=&type=&q=` | ADMIN | Monitor all transactions |
| GET | `/api/admin/transactions/summary` | ADMIN | Today's count and volume, failures, 14 day chart |
| POST | `/api/admin/transactions/deposit` | ADMIN | Teller deposit into any active account |

## Run

```bash
../novabank-platform/dev.sh db     # Db2 only, then run "TransactionService (local)" in IntelliJ
```

Extra variables: `ACCOUNT_SERVICE_URL` (local `http://localhost:8082`), `MAX_TRANSFER_AMOUNT` (default 10000.00).

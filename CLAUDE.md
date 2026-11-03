# CLAUDE.md: novabank-transaction-service

Part of NovaBank, a banking demo split into six repos that sit side by side in one folder:
novabank-ui (Angular), novabank-api-gateway, novabank-auth-service, novabank-account-service,
novabank-transaction-service (Spring Boot 3.5, Java 21) and novabank-platform (Db2 scripts,
Docker Compose, GCP bootstrap, Postman collection). See ../novabank-platform/README.md.

## Commands
- Build and test: `mvn verify` (unit tests, then `*IT` Db2 integration tests via failsafe; need Docker and
  `../novabank-platform`). Unit tests only: `mvn verify -DskipITs`. One test: `mvn test -Dtest=Class#method`
- Run locally: start Db2 with `../novabank-platform/dev.sh db`, then `mvn spring-boot:run`
  (profile `local` is the default) or the IntelliJ run configuration in `.run/`.
- Whole stack: `../novabank-platform/dev.sh up`

## Conventions
- Java 21, records for DTOs, constructor injection, no Lombok.
- Errors leave the service as RFC 9457 ProblemDetail via GlobalExceptionHandler.
  Throw `ApiException` for business errors.
- The Db2 schema is owned by `novabank-platform/db2/schema`. `ddl-auto` is `none`.
  A column change means editing those SQL scripts, not entities alone.
- Db2 rejects untyped `? IS NULL` parameters, so optional filters use JPA Specifications.
- Money is `BigDecimal` with scale 2. Never double.
- Timestamps are `Instant` and the JVM runs in UTC.
- Secrets come from environment variables. Never commit real values.
- Every POST/PUT/PATCH/DELETE handler under `/api` needs `@Audited` (`AuditCoverageTest` enforces it); GET handlers
  must not have it. Add facts with `Audit.target` / `Audit.detail` / `Audit.reason`, and never pass a password,
  hash, token or request body to them.
- Environments: `application-local.yml`, `application-dev.yml`, `application-preprod.yml`,
  and the Cloud Run definitions in `deploy/environments/`.

## Reconciliation
- `PendingTransactionReconciler` settles PENDING rows against account-service's ledger. It must stay safe on
  several instances: claim with `claimForReconciliation`, and write final states only via the conditional
  `completePending` / `failPending` updates (never `save()` a reconciled entity). `ReconciliationIT` proves it.

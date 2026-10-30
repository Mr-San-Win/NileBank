# Architecture

VaultBank is a same-origin Spring Boot monolith serving JSON APIs and static browser assets. All funds are simulated THB. PostgreSQL is the source of truth; Flyway owns migrations and Hibernate validates mappings.

## Request flow
Browser → Spring Security (session and CSRF) → validated controller DTO → transactional service → JPA repository → PostgreSQL. Responses contain DTOs, never password hashes or persistence entities.

## Consistency model
Account balances use numeric(19,2) / BigDecimal. Monetary services acquire PostgreSQL pessimistic write locks before reading balances. Transfers lock both account rows in deterministic UUID order, validate ownership and eligibility, then debit, credit, and insert an audit record within one transaction. A failure rolls all changes back.

Transfer keys are scoped to the sender. The locked source account serializes competing requests; the committed transaction stores the original key and transfer parameters under a database uniqueness constraint. Same-key retries return the original record; changed parameters conflict. Failed requests roll back the key and may be retried.

## Tradeoffs
One account per user, one currency, bounded amounts, synchronous requests, no external payment integrations. Audit history records successful operations only. This is not a production double-entry ledger; no financial compliance, settlement, reconciliation, or fraud controls are provided.

## Schema and audit

Flyway V1 initializes application metadata; V2 creates users; V3 creates accounts and financial history; V4 adds scoped transfer keys; V5 makes history append-only with a PostgreSQL trigger. Users and accounts use UUID primary keys. Each account references one owner under a unique constraint. Transactions reference source/destination account rows; a shape constraint enforces which references apply to deposits, withdrawals and transfers. Unique email/account number/reference/key constraints protect identity. History indexes cover source/destination plus time. Account status and currency are database constrained; negative balances are rejected at both service and database boundaries.

The account version column is an additional optimistic safeguard; pessimistic write locks are the primary monetary concurrency mechanism. There are no endpoints for deleting accounts, editing balances, changing owners, or mutating history. The database owner can still disable a trigger, so this is not independently tamper-proof storage.

## Authentication and browser

BCrypt strength 12 hashes passwords. A DAO authentication provider loads users by normalized email and uses their UUID as the session principal. JSON login invokes session fixation protection and CSRF authentication strategies, then explicitly saves the SecurityContext in the HTTP session. Logout is handled by Spring Security, invalidates the session and deletes its cookie.

CSRF is session-backed. The default XOR request handler delivers a masked token through JSON; the frontend sends that token in the returned header. A fresh token is fetched after login. Every POST is protected, including registration/login. SameSite=Lax and HTTP-only cookies are enabled; secure cookies are opt-in for localhost compatibility and mandatory behind HTTPS outside local development. Content security policy limits resources to the application's origin and forbids embedding.

The static frontend uses ES modules and Fetch with same-origin cookies. No authentication secrets enter localStorage. API values are rendered as text, not injected HTML. BigDecimal API response values are decimal strings, and browser display formatting avoids floating-point money arithmetic. Form controls use labels and native dialogs; dashboard navigation and tables adapt to mobile screens.

## Operations and failure behavior

Deposits/withdrawals acquire the owned row lock and validate amount, status and funds before updating the managed account and inserting audit history. Transfer pre-lock queries return scalar IDs, avoiding an already managed stale account snapshot. Both rows are then locked in UUID string order. Same-source competing operations serialize even when recipients differ. A successful prior key is compared before attempting a new movement. The original result is returned for matching replays, including if an account later freezes.

An unchecked business or persistence exception rolls back the transaction. A tested recipient-capacity failure occurs after sender debit and proves that the debit/history/key do not survive a failed transfer. Failed requests do not reserve a key permanently. Unique constraints are a backstop rather than a substitute for locking.

Default PostgreSQL READ COMMITTED isolation is sufficient for this locking protocol. No external calls occur under the locks. Deterministic ordering reduces deadlocks but does not claim universal deadlock elimination. Lock failures return a conflict; callers can retry a transfer with its original key. Deposit/withdrawal retries after ambiguous network responses require checking history because those operations have no idempotency key.

## Runtime and verification

Docker uses a Maven/Java 21 build stage, a non-root Java 21 runtime, a database-aware application health check, a ready-before-start PostgreSQL dependency and a persistent volume. The application is bound to localhost; the database is not published. Runtime configuration comes from environment variables. Session state is in memory; balances/history persist across application restarts.

JUnit tests exercise actual PostgreSQL through Testcontainers. Concurrency tests use barriers and multiple independent service transactions, not a single shared test transaction. A real HTTP client test verifies cookies, CSRF, registration/login/logout and an end-to-end banking journey. Browser QA separately verified the frontend's real API flows, desktop and mobile views. CI runs the same suite on a Docker-capable runner; execution status is recorded separately from configuration.

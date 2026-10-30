# NileBank delivery report

## 1. Status
The local full-stack MVP is implemented and verified in Ubuntu WSL. The Docker application is running at http://localhost:8080. All six implementation milestones and the NileBank rename have been pushed. GitHub Actions is running; its final result is reported separately.

## 2. Features
Registration/login/logout, one virtual account per user, zero starting balance, exact simulated THB deposits/withdrawals, atomic transfers with persisted idempotency, paginated transaction history, profile details, responsive dashboard, confirmation dialogs and error/loading/empty states.

## 3. Architecture
A same-origin Java 21 / Spring Boot layered monolith. Controllers validate HTTP DTOs, services enforce ownership and transaction boundaries, repositories persist through JPA, and static ES modules call real APIs.

## 4. Database
PostgreSQL users, accounts and successful financial transaction history use UUIDs, foreign keys, indexes, normalized unique email, unique account/reference IDs, numeric(19,2) money, nonnegative balances, and one account per owner. Six Flyway migrations succeed; old migration checksums are preserved. Audit rows reject updates/deletes through a trigger. Existing data survived the rename unchanged.

## 5. Security
BCrypt strength 12, server-side sessions, HTTP-only/SameSite cookies, session fixation protection, CSRF token delivery and rotation, POST logout invalidation, service ownership checks, validation, consistent JSON errors, CSP and no password/hash entity exposure. HTTPS and secure cookies are required beyond localhost.

## 6. Tests
Native Ubuntu Java 21 / Maven 3.9.11 verify: 31 tests passed, 0 failures/errors/skips, executable JAR packaged. Real PostgreSQL Testcontainers proves concurrent withdrawal protection, transfer conservation, duplicate replay and rollback after sender debit. Real HTTP tests cover session cookies and CSRF. JavaScript syntax check passed via standard input to avoid Windows UNC package lookup. python3 scripts/smoke.py passed against the WSL-built Docker app.

## 7. Docker
WSL build/start and app/database readiness passed. V1-V6 migrations are successful; runtime is non-root UID 10001. The existing Docker namespace/volume and ignored environment were preserved. Account count / total balance before and after migration were both 4 / 180.50 THB. Later smoke/browser tests intentionally added more fictional data.

## 8. Git
Canonical folder: /home/san_win/NileBank.
Repository: https://github.com/Mr-San-Win/NileBank
Branch: codex/nilebank-mvp.
Verified push head before this report receipt: ef04ce09b1ca782c458114024900e8144dad4faa.
Main remained 1315d16e133b7e75a00a89a86766637e16798ebc.

## 9. Pushed milestones
All six milestone commits (909ffe0, 4f9b70c, 2e818c2, 5e0db8e, 21d7a80, d91a063) plus the tested rename 5e086c0 and history-preserving merge ef04ce0 are on the development branch. See BUILD_LOG.md for full hashes, test counts and push evidence. No main merge or force-push occurred.

## 10. Limitations
Educational simulated money only; no real payments or production double-entry ledger. One account/THB only; no password reset, MFA, rate limiting or admin freeze UI. Sessions are in memory. Transfer keys persist in the browser only within its tab; inspect history after a reload or uncertain response. Deposits/withdrawals have no idempotency key, so ambiguous responses must be checked before retry. No independent security audit or performance benchmark is claimed. Earlier Windows and nested WSL copies remain backups; current work is only in /home/san_win/NileBank.

## 11. Run
In Ubuntu: cd /home/san_win/NileBank, then docker compose up --build.
For a fresh clone, first copy .env.example to .env and choose a local password. Do not change an initialized volume's credentials without deliberately migrating them. Open http://localhost:8080. No seeded credentials exist; register fictional users.

## 12. Interview preparation
Study BigDecimal and fixed-precision money; transaction atomicity versus row locking; deterministic lock ordering; persisted idempotency and retries; session authentication and CSRF. Explain why this simulator's audit history is not a real financial ledger.

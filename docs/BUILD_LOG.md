# Build log
## Environment
- Empty working tree, existing unborn Git repository; no remote or Git author configured.
- Project-local Java 21, Maven 3.9.11 and GitHub CLI downloaded to ignored .tools.
- Docker Desktop startup requested; daemon access verification pending.
- Branch codex/vaultbank-mvp created. No push attempted yet.

## Milestone 1 — completed
- Spring Boot 3.5.16, Java 21, PostgreSQL, Flyway, database-aware health endpoint, Compose, non-root runtime container.
- Executed Java 21 Maven `mvn -B test`: BUILD SUCCESS, 1 PostgreSQL Testcontainers test, 0 failures/errors/skips. Spring context started, Flyway V1 ran and health queried PostgreSQL successfully.
- GitHub connector confirms Mr-San-Win; local author uses verified profile and GitHub noreply address. CLI is unauthenticated, so repository creation/push blocked. No remote configured.
- Commit: `909ffe08483ed04a158671e7f1dc47ee61cc507c`; push blocked by unauthenticated gh and absent remote. Docker Compose full-stack smoke test deferred to delivery.

## Milestone 2 — completed
- UUID users, normalized unique email, BCrypt strength 12, JSON registration/login/me, server-side sessions, session ID rotation, logout invalidation and CSRF token rotation.
- CSRF protects anonymous registration/login as well as authenticated mutations. JSON errors, validation and same-origin CSP enabled.
- Executed `mvn -B test` on Java 21 and real PostgreSQL: 8 tests, 0 failures/errors/skips, BUILD SUCCESS. Actual delivered token, old-token rejection after login and logout invalidation verified.
- Commit: `4f9b70c24dbfae3937ab6d1ad36aa503e263bbfa`. Push remains blocked by CLI login; no repeated push attempts.

## Milestone 3 — completed
- One zero-balance account per owner; service ownership checks; simulated deposits, withdrawals, paginated history, fixed-precision limits, frozen-account checks and PostgreSQL write locks.
- Executed `mvn -B test`: 16 tests, 0 failures/errors/skips. Twelve simultaneous 80 THB withdrawals from 100 THB produced exactly one success and a 20 THB balance. Invalid values and unauthorized operations did not change balances.
- Commit hash recorded after creation. Push blocked by unauthenticated CLI/no remote.


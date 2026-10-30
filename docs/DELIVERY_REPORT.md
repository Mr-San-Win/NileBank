# Final delivery verification — 2026-10-30

NileBank is a completed educational simulator with registration/login, one THB account, deposits/withdrawals, atomic idempotent internal transfers, immutable paginated history, real daily-reference THB/USD/EUR exchange, fictional partner-bank movements and separate manual finance tracking/budgets/goals/insights. No real money or bank credentials are used.

Java 21, Spring Boot 3.5.16, PostgreSQL 17, Flyway V1–V9 and a same-origin ES module frontend form a layered monolith. Session/CSRF protection, BCrypt, service ownership checks, BigDecimal, database constraints and explicit account row locks protect the demonstrated flows. See ARCHITECTURE.md and INTERVIEW_GUIDE.md.

Final publication-time Maven verify passed 56 tests with zero failures/errors/skips. Both HTTP smoke scripts passed, including actual ECB provider retrieval. Docker app/database are healthy; desktop and mobile browser checks were recorded previously. Run `docker compose up --build` after copying .env.example to ignored .env and choosing a local password, then open http://localhost:8080. A fresh database has no seeded login; register fictional users.

Local delivery branch: codex/nilebank-mvp. Exact commit and publication/CI receipts are in BUILD_LOG.md. At verification, configured origin returned Repository not found and local GitHub CLI was logged out; publication must not be described as successful until remote verification succeeds.

Limits: daily reference rates, external provider availability, fictional partners only, manual tracker records, planning allocations that do not reserve funds, in-memory sessions, no recovery/MFA/rate limiting, no production double-entry ledger or real payment integration. Deposits/withdrawals lack idempotency keys. No independent security audit or performance claims.

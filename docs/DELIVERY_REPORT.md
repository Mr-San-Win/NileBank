# Final delivery verification — 2026-10-30

NileBank is a completed educational simulator with registration/login, one THB account, deposits/withdrawals, atomic idempotent internal transfers, immutable paginated history, real daily-reference THB/USD/EUR exchange, fictional partner-bank movements and separate manual finance tracking/budgets/goals/insights. No real money or bank credentials are used.

Java 21, Spring Boot 3.5.16, PostgreSQL 17, Flyway V1–V9 and a same-origin ES module frontend form a layered monolith. Session/CSRF protection, BCrypt, service ownership checks, BigDecimal, database constraints and explicit account row locks protect the demonstrated flows. See ARCHITECTURE.md and INTERVIEW_GUIDE.md.

Final publication-time Maven verify passed 56 tests with zero failures/errors/skips. Both HTTP smoke scripts passed, including actual ECB provider retrieval. Docker app/database are healthy; desktop and mobile browser checks were recorded previously. Run `docker compose up --build` after copying .env.example to ignored .env and choosing a local password, then open http://localhost:8080. A fresh database has no seeded login; register fictional users.

Local delivery branch: codex/nilebank-mvp. Exact commit and publication/CI receipts are in BUILD_LOG.md. Publication succeeded to the recreated repository https://github.com/Mr-San-Win/NileBank. (trailing period). Source commit 44a6a51a0bb31e7842b58c78258c3f6a1df85ace is on codex/nilebank-mvp; main is unchanged. GitHub Actions run 41083808889 succeeded for this source commit, including PostgreSQL verification, both JavaScript checks and Docker build. SSH authenticated successfully; CLI login was unnecessary because the repository already existed.

Limits: daily reference rates, external provider availability, fictional partners only, manual tracker records, planning allocations that do not reserve funds, in-memory sessions, no recovery/MFA/rate limiting, no production double-entry ledger or real payment integration. Deposits/withdrawals lack idempotency keys. No independent security audit or performance claims.

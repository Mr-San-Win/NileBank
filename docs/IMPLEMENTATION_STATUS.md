# Implementation status

## Local completion checkpoint — 22 November 2026

Branch: `feature/nilebank-completion`. Work is local only: no push, merge or public
deployment is authorized. The original FX-001 implementation and all applied
V1–V9 migrations were preserved. New migrations are V10–V15.

| Milestone | Status | Verified implementation / remaining limits |
| --- | --- | --- |
| AUTH-001 | Implemented | Optional default-off Google OIDC, Spring-managed code flow, PKCE/state/nonce/token validation, UUID identity mapping, collision rejection and concurrent provisioning. Real Google credentials/consent remain external and unverified. |
| AUTH-002 | Partially implemented | Explicit password-proof linking/unlinking, last-method protection, method settings, password change, database credential-version revocation, redacted security events and bounded per-process IP limits. Recovery/email verification lack verified delivery; MFA is not implemented. |
| FX-002 | Implemented | Seven wallets, all 42 directions under a complete snapshot, JPY precision, exact settlement, persisted quotes/replay, pinned CBM reference source. ECB intentionally excludes MMK. Rates are delayed official references, not executable market prices. |
| FIN-001 | Implemented | Owned categories, archival, entry editing/filtering/sorting, monthly budgets/summaries/comparisons, recurring daily/weekly/monthly entries with idempotent bounded generation and documented date policies. Tracker entries never debit simulated funds. |
| SAVE-001 | Implemented | Up to ten named THB savings accounts, targets/status, atomic IN/OUT, parent-first locking, retry keys and immutable history. No interest; planning goal allocations remain separate. |
| BANK-001 | Implemented hardening | Cash retry records, ownership/status/precision checks, full banking/FX/partner/savings concurrency and rollback regression coverage. Legacy cash calls without keys remain non-idempotent. No double-entry ledger or reconciliation engine. |
| UI-001 | Implemented and exercised | Existing vanilla-JS interface extended with security, seven currencies, categories/recurrence and real savings. Actual Chromium forms, confirmation flows, navigation and 390px overflow checks passed. No formal accessibility audit. |
| TEST-001 | Implemented | PostgreSQL 17 Testcontainers, deterministic OIDC protocol fixtures, financial invariants, recurrence, revocation and production-profile HTTP tests. Final totals below. |
| DOC-001 | Implemented | README, API/architecture, identity/security, currencies, recurrence, savings, migrations, testing, deployment and privacy documentation. |
| DEPLOY-001 | Preparation implemented | Secure production profile, environment checks, exact configured proxy trust, structured logs, private DB, non-superuser app role, resource limits and read-only app filesystem. Local template/restore checks passed. Public environment verification remains outstanding. |

## Executed evidence

- Savings checkpoint: 180 tests passed, zero failures/errors/skips.
- Banking checkpoint: 186 tests passed, zero failures/errors/skips.
- Production-preparation checkpoint: 194 tests passed, zero failures/errors/skips.
- Final verification: **195 tests passed, zero failures/errors/skips**, including a real untrusted socket peer attempting to spoof forwarded HTTPS.
- Every frontend JS module passed syntax checks; seven exact currency-format assertions passed.
- Isolated Chromium: registration/login, account creation, deposit/withdrawal,
  reviewed internal transfer, savings IN/OUT, category/recurrence/budget/expense,
  real reference quote/execution, fictional partner linking/movement, navigation,
  390px overflow checks and logout passed; no JavaScript page errors.
- Root Compose image build/start/readiness and HTTP banking smoke passed using
  fresh QA storage, without changing existing demo databases.
- Custom-format PostgreSQL backup restored to a separate fresh database; audit
  row counts matched. This is not a complete disaster-recovery certification.
- Production template built/start/readiness passed; app role had no superuser,
  database-creation or role-creation privileges. ECS structured logging was verified
  from actual production-container output. QA containers were stopped/removed.

## Recovery, MFA and operational blockers

No recovery endpoint or insecure email shortcut is provided. Recovery requires a
verified delivery mechanism, hashed short-lived single-use tokens and ownership
checks. MFA needs secure seed storage, pending-auth enforcement and recovery code
handling; it is explicitly unavailable. Google-only users cannot invent a local
password or unlink their final method. Password proof is currently required to
initiate explicit linking.

Public registration still needs trusted HTTPS ingress/DNS, actual proxy-path
verification, aggregate abuse controls, Google configuration if enabled, encrypted
off-host backups and monitoring, support/privacy/retention decisions, and an approved
data export/deletion workflow. The production template app owns its schema for Flyway;
a separately privileged migrator/runtime role design remains a security improvement.

## Readiness

- Local development: verified for implemented features.
- Integration tests: verified on real PostgreSQL 17.11.
- Staging: preparation available; actual hosting/TLS/Google integration not verified.
- Production: **not established**. No public deployment, performance benchmark,
  independent security assessment or legal compliance claim.

## Continuation constraints

Do not alter applied migrations, discard local work or delete persistent financial
data. No remote publication or deployment without explicit approval. See
[deployment runbook](DEPLOYMENT.md) for environment-dependent work and
[testing guide](TESTING.md) for reproducible commands.

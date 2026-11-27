# Implementation status

## Local completion checkpoint — 23 November 2026

Branch: `feature/nilebank-completion`. Work is local only: no push, merge or public
deployment is authorized. The original FX-001 implementation and all applied
V1–V9 migrations were preserved. New migrations are V10–V18.

| Milestone | Status | Verified implementation / remaining limits |
| --- | --- | --- |
| AUTH-001 | Implemented | Optional default-off Google OIDC, Spring-managed code flow, PKCE/state/nonce/token validation, UUID identity mapping, collision rejection and concurrent provisioning. Real Google credentials/consent remain external and unverified. |
| AUTH-002 | Implemented with documented limits | Explicit password-proof linking/unlinking, last-method protection, method settings, password change, database credential-version revocation, redacted security events and bounded per-process IP limits. Default-off SMTP recovery and encrypted TOTP MFA are implemented. Both login paths enforce pending-factor authorization. Real SMTP/Google delivery/configuration remains external; profile email confirmation is implemented; Google-only MFA enrollment/management now uses explicit recent same-identity Google proof when configured. |
| FX-002 | Implemented | Seven wallets, all 42 directions under a complete snapshot, JPY precision, exact settlement, persisted quotes/replay, pinned CBM reference source. ECB intentionally excludes MMK. Rates are delayed official references, not executable market prices. |
| FIN-001 | Implemented | Owned categories, archival, entry editing/filtering/sorting, monthly budgets/summaries/comparisons, recurring daily/weekly/monthly entries with idempotent bounded generation and documented date policies. Tracker entries never debit simulated funds. |
| SAVE-001 | Implemented | Up to ten named THB savings accounts, targets/status, atomic IN/OUT, parent-first locking, retry keys and immutable history. No interest; planning goal allocations remain separate. |
| BANK-001 | Implemented hardening | Cash retry records, ownership/status/precision checks, full banking/FX/partner/savings concurrency and rollback regression coverage. Legacy cash calls without keys remain non-idempotent. No double-entry ledger or reconciliation engine. |
| UI-001 | Implemented and exercised | Existing vanilla-JS interface extended with security, seven currencies, categories/recurrence and real savings. Actual Chromium forms, confirmation flows, navigation and 390px overflow checks passed. No formal accessibility audit. |
| TEST-001 | Implemented | PostgreSQL 17 Testcontainers, deterministic OIDC protocol fixtures, financial invariants, recurrence, revocation and production-profile HTTP tests. Final totals below. |
| DOC-001 | Implemented | README, API/architecture, identity/security, currencies, recurrence, savings, migrations, testing, deployment and privacy documentation. |
| DEPLOY-001 | Preparation implemented | Secure production profile, environment checks, exact configured proxy trust, structured logs, private DB, non-superuser app role, resource limits and read-only app filesystem. Local template/restore checks passed. Host Nginx ingress template, GPG-encrypted backup tooling and certificate-validating health probe are implemented and locally tested; external activation remains outstanding. |

## Executed evidence

- Savings checkpoint: 180 tests passed, zero failures/errors/skips.
- Banking checkpoint: 186 tests passed, zero failures/errors/skips.
- Production-preparation checkpoint: 194 tests passed, zero failures/errors/skips.
- Previous final checkpoint: **195 tests passed**, including an untrusted-peer forwarding-header test.
- Recovery checkpoint: **215 tests passed, zero failures/errors/skips**, including real SMTP transport, TLS failure, token concurrency and session revocation.
- MFA checkpoint: **239 tests passed, zero failures/errors/skips**, including RFC vectors, encryption/key rotation, concurrent OTP/code consumption, password-recovery bypass prevention and a real signed Google callback requiring MFA.
- Final Chromium MFA walkthrough passed enrollment, OTP replay rejection, pending API denial, TOTP login, recovery-code replacement/login and secure factor removal.
- Email-confirmation checkpoint: **250 tests passed, zero failures/errors/skips**; separate token purposes, delivered ownership proof, concurrency, CSRF and default-off compatibility.
- Chromium email confirmation passed: owned request, same-tab fragment removal, no automatic login, and confirmed profile status after normal sign-in.
- Final remaining-scope suite: **258 tests passed, zero failures/errors/skips**. Signed-provider tests cover Google-only enrollment/management, unchanged null passwords, essential auth_time requests and rejection of missing/stale/future/malformed claims or wrong subjects.
- Every frontend JS module passed syntax checks; seven exact currency-format assertions passed.
- Isolated Chromium: registration/login, account creation, deposit/withdrawal,
  reviewed internal transfer, savings IN/OUT, category/recurrence/budget/expense,
  real reference quote/execution, fictional partner linking/movement, navigation,
  390px overflow checks and logout passed; no JavaScript page errors.
- Recovery browser verification passed real local SMTP request/delivery, same-tab
  fragment capture/removal, password reset, new-password login and old-session revocation.
- Root Compose image build/start/readiness and HTTP banking smoke passed using
  fresh QA storage, without changing existing demo databases.
- Custom-format PostgreSQL backup restored to a separate fresh database; audit
  row counts matched. This is not a complete disaster-recovery certification.
- Production template built/start/readiness passed; app role had no superuser,
  database-creation or role-creation privileges. ECS structured logging was verified
  from actual production-container output. QA containers were stopped/removed.

## External setup and enrollment limitations

Recovery now uses generic asynchronous requests, hashed single-use tokens, real SMTP
transport and session revocation; production delivery requires external setup. It
never creates credentials for a Google-only account or links identities by email. TOTP MFA now has encrypted seeds, pending-only authentication, replay/attempt controls
and hashed recovery codes. Enrollment uses local password proof or, for Google-only profiles, explicit recent
Google identity proof, with operator-managed encryption keys. Offline single-use
recovery codes are provided; no insecure assisted bypass for losing all factors is
implemented. Profile email confirmation preserves existing login permissions. Real provider/hosting setup is external. Google-only users cannot invent a local
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

## Final scope boundary

The approved local banking, FX, finance, savings, UI, authentication/security, tests,
documentation and deployment-preparation features are implemented. Remaining work
is external activation/verification and explicit operating policy: real Google
clients and auth_time settings, SMTP sender/inbox delivery, TLS/hosting/proxy trust,
monitoring/off-host backups and privacy/support ownership. Mandatory email enforcement
for existing/new public users needs a deliberate rollout decision; local compatibility
is preserved. No push or deployment is authorized here. Recovery codes are the
implemented lost-authenticator path; no email-only bypass for lost-all-factors exists.

## Operational tooling checkpoint

The full Maven suite was rerun: 258 passed, zero failures/errors/skips. Six
operational unit tests passed. Isolated PostgreSQL encrypted backup/restore,
permissions, overwrite refusal, corrupted ciphertext rejection and failed-dump
cleanup passed. Real local HTTPS accepted a trusted QA certificate and rejected
an untrusted one. The Nginx template passed syntax validation with actual QA
certificate files; real proxy forwarding/cookies/rate behavior still needs staging.
See [operational preparation](OPERATIONS.md). No dependencies, application logic
or migrations changed. Hosting is not selected, and no public deployment is authorized.

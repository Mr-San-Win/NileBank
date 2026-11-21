# Implementation status and continuation

## Verified checkpoint: AUTH-001

The original FX-001 tree is preserved. AUTH-001 Phase 1 was audited and committed
locally as `c1ceb44` on `feature/nilebank-completion`. No remote publication or
public deployment is authorized by this workstream.

Google OIDC login is implemented, default-off, using Spring Security OAuth2 Client,
server-side sessions, required S256 PKCE and the existing internal UUID ownership
model. Tokens stay server-side; email equality does not link users. Local verification
passed 112 tests on PostgreSQL 17.11, including protocol/filter tests using a local
signed-token provider. JS syntax and Compose configuration checks passed. The local single-service image
`nilebank:auth001-local` built successfully; no application stack was started or deployed.
Real Google Cloud consent/client configuration, browser login against Google and
public reverse-proxy behavior still require external setup and verification.

## Remaining approved milestones (not claimed complete)

| Milestone | Existing baseline | Remaining work |
| --- | --- | --- |
| AUTH-002 | Explicit password-proof linking/unlinking, method settings, password changes, database session revocation, bounded IP limits and redacted security events; 132 tests pass | Recovery lacks verified delivery and remains unavailable. MFA is not implemented. Production ingress abuse controls still require setup. |
| FX-002 | Seven-currency wallets, JPY precision, all 42 directions, exact snapshots, atomic execution/replay and provider attribution; 143 tests pass | CBM default fetch verified for all seven; ECB mode intentionally has no MMK quotes. No public deployment or market-price claim. |
| FIN-001 | Entry editing/filtering, owned categories and archival, budgets/insights, daily/weekly/monthly schedules, bounded idempotent generation; 165 tests pass | Browser walkthrough pending. Tracker data remains separate from bank balances. |
| SAVE-001 | Planning goals and allocations only, separate from banking | Real simulated savings accounts and atomic audited main/savings transfers; ownership, retry keys, concurrency and conservation tests. |
| BANK-001 | Existing banking/FX/partner regression tests remain passing | Re-review new financial operations as added, including retry behavior and audit coverage. No wholesale rewrite. |
| UI-001 | Existing responsive banking/finance screens plus optional Google control | Security, recurring transactions and real savings screens; coherent navigation and verified browser/mobile flows. |
| TEST-001 | 112 passing tests at AUTH-001 checkpoint | Extend coverage for each subsequent milestone; preserve baseline; do not substitute mocked authentication for protocol tests. |
| DOC-001 | Updated README, API, architecture and authentication guide | Document remaining modules and schema changes once implemented; complete setup and operational guides. |
| DEPLOY-001 | Local Compose, health check, default-off Google config | Production profile validation, trusted TLS proxy, backups/restore exercise, redacted logging, monitoring, limits, retention/deletion policy and deployment runbook. No public deployment performed. |

## Next safe implementation sequence

AUTH-002 core, FX-002 and FIN-001 are locally implemented. The latest full suite has
165 passing tests; seven frontend formatting assertions also pass. Continue SAVE-001:
actual simulated THB savings accounts and main/savings movements, preserving existing
planning goals as a separate domain. Then complete banking/UI review, browser checks,
operational documentation and production preparation. Recovery and MFA are unavailable.

## Commands and constraints

From `backend/`: `sh mvnw -B clean verify`. The current WSL verification uses the
existing ignored cache via `-Dmaven.repo.local=/home/san_win/NileBank/.tools/m2`;
that is an environment-specific optimization, not a prerequisite for another machine.
Docker must be running; PostgreSQL integration tests do not skip themselves.

Never edit applied V1-V13 migrations, discard local work, fabricate live rates,
create real credentials, push, merge or deploy without the relevant authorization.
Do not treat this checkpoint as completion of the entire approved expansion.

## Readiness

Local build/integration-test ready for implemented features. Real Google setup is
unverified. Staging and production readiness are not established. The application
still lacks password recovery, MFA, distributed ingress abuse controls and
complete privacy/operations controls required by the broader plan.

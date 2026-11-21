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
| AUTH-002 | Sessions, CSRF, BCrypt, external identity foundation | Explicit recent-reauthenticated link/unlink; method settings; password changes and session revocation; abuse controls; redacted security events. Recovery requires verified delivery. MFA remains unimplemented. |
| FX-002 | THB/USD/EUR, fixed rounding, exact snapshots, atomic execution/replay | Seven-currency metadata, wallet/schema changes, currency-specific source precision, UI formatting; research provider coverage including MMK. Do not fabricate unavailable rates. |
| FIN-001 | Manual THB entries, deletion/month paging, fixed categories, monthly budgets and deterministic insights | Editing, user categories and safe deletion, richer filters/sorts, recurring rules and idempotent occurrence generation. |
| SAVE-001 | Planning goals and allocations only, separate from banking | Real simulated savings accounts and atomic audited main/savings transfers; ownership, retry keys, concurrency and conservation tests. |
| BANK-001 | Existing banking/FX/partner regression tests remain passing | Re-review new financial operations as added, including retry behavior and audit coverage. No wholesale rewrite. |
| UI-001 | Existing responsive banking/finance screens plus optional Google control | Security, recurring transactions and real savings screens; coherent navigation and verified browser/mobile flows. |
| TEST-001 | 112 passing tests at AUTH-001 checkpoint | Extend coverage for each subsequent milestone; preserve baseline; do not substitute mocked authentication for protocol tests. |
| DOC-001 | Updated README, API, architecture and authentication guide | Document remaining modules and schema changes once implemented; complete setup and operational guides. |
| DEPLOY-001 | Local Compose, health check, default-off Google config | Production profile validation, trusted TLS proxy, backups/restore exercise, redacted logging, monitoring, limits, retention/deletion policy and deployment runbook. No public deployment performed. |

## Next safe implementation sequence

1. Start AUTH-002 on the existing development branch without rewriting commits.
2. Add explicit authentication-method management and recent reauthentication before
   linking. Bind linking intent to server session and OAuth state; use the verified
   callback's issuer/subject, never a JSON claims endpoint or email match.
3. Preserve the initiating internal UUID during linking; reject identities owned by
   another user, serialize credential changes and prevent removal of the last method.
4. Add session revocation for password changes, abuse controls and safe event logging.
   Test takeover attempts, stale intent, replay, concurrent binding and revocation.
5. Evaluate recovery delivery and MFA independently. No email shortcut or partially
   enforced MFA is acceptable. Document unavailable external delivery instead.
6. Run full Maven verification, check JS/Compose, review diff and secrets, update
   documentation and create a local milestone commit. Continue FX-002 afterward.

## Commands and constraints

From `backend/`: `sh mvnw -B clean verify`. The current WSL verification uses the
existing ignored cache via `-Dmaven.repo.local=/home/san_win/NileBank/.tools/m2`;
that is an environment-specific optimization, not a prerequisite for another machine.
Docker must be running; PostgreSQL integration tests do not skip themselves.

Never edit applied V1-V10 migrations, discard local work, fabricate live rates,
create real credentials, push, merge or deploy without the relevant authorization.
Do not treat this checkpoint as completion of the entire approved expansion.

## Readiness

Local build/integration-test ready for implemented features. Real Google setup is
unverified. Staging and production readiness are not established. The application
still lacks rate limiting, password recovery, MFA, linked-account management and
complete privacy/operations controls required by the broader plan.

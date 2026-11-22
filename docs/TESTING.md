# Verification guide

## Mandatory backend suite

Java 21 and a running Linux Docker engine are required. PostgreSQL Testcontainers
uses `postgres:17-alpine`; no H2 substitute or silent Docker skip is used.

```sh
cd backend
sh mvnw -B clean verify
```

PowerShell may use `mvnw.cmd`. An existing local Maven cache can be passed with
`-Dmaven.repo.local=...`; that is optional and machine-specific. Results are in
`backend/target/surefire-reports/`. Tests create isolated PostgreSQL containers,
including migration upgrade fixtures, concurrency, OIDC protocol, session security,
finance recurrence and money conservation checks. They do not need Google secrets.

## Frontend checks

```sh
for file in frontend/*.js; do node --check --input-type=module < "$file"; done
node scripts/check_currency_format.mjs
```

Seven exact formatting assertions include JPY, MMK and balances larger than the
JavaScript safe integer range. This does not substitute for a browser walkthrough.

## Isolated browser walkthrough (Linux / WSL)

Optional Playwright tooling is separate from application and Maven dependencies.
Install it only when performing browser QA:

```sh
python3 -m pip install --target .tools/browser-lib -r scripts/requirements-browser.txt
PYTHONPATH=.tools/browser-lib PLAYWRIGHT_BROWSERS_PATH=.tools/pw-browsers \
  python3 -m playwright install chromium --only-shell
# Complete the Maven build first; do not repackage the JAR during the walkthrough.
python3 scripts/browser_walkthrough.py
```

Chromium needs its normal Linux shared libraries. The script starts a new disposable
PostgreSQL container with no persistent volume, generates its test-only password in
memory, starts the built JAR on a free loopback port, and cleans up only its own
process/container. It never connects to the existing Compose database. Runtime logs
are ignored under `.tools/`. All user profiles and financial activity are fictional.

It exercises real HTML controls and fetch requests: registration/login, account
creation, deposits/withdrawals, reviewed internal transfer, savings in/out, custom
categories, recurring generation, budgets, expenses, seven-currency wallets, a quote
and exchange, fictional partner linking/movement, history/settings navigation,
390px mobile overflow checks and logout. It also uses a loopback-only SMTP sink
to test recovery requests, same-tab reset links, fragment removal, new-password login
and revocation of an existing session. It supplies a random in-memory QA encryption
key and tests authenticator enrollment, OTP replay rejection, pending API denial,
TOTP login, code replacement/recovery login and factor removal. No real emails are sent. Google is disabled; its protocol is tested
against a deterministic local signed-token provider in the Maven suite.

The exchange walkthrough intentionally uses the configured real delayed-reference
provider. A provider/network failure fails this optional check; it never substitutes
invented rates. The mandatory Maven financial tests use fixed snapshots.

## Existing HTTP smoke scripts

`python3 scripts/smoke.py [base-url]` and `smoke_extensions.py [base-url]` run against
an already running app and create fictional persisted records there. Prefer the
isolated browser script when existing demo data must remain untouched. Older smoke
expectations must be reviewed whenever API contracts or currency coverage change.

## Evidence boundaries

Passing tests do not establish independent security assurance, performance capacity,
real Google consent/client configuration, or production readiness. Remote CI results
cannot include unpublished local commits. See implementation status for actual local
results and the deployment runbook for environment verification still required.

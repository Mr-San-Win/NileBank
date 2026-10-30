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
- Commit: `2e818c2bed8bbfb88360777d4334a023b4b46e19`. Push blocked by unauthenticated CLI/no remote.

## Milestone 4 — completed
- Atomic transfers with deterministic UUID lock ordering; scalar pre-lock lookups avoid stale managed balance snapshots. Sender-scoped idempotency keys persist in the same transaction under a database unique constraint.
- First `mvn -B test`: 24 tests, 1 failure: missing Idempotency-Key returned 500. Added binding-error mapping to 400 and reran the unchanged tests.
- Retest: 24 tests, 0 failures/errors/skips, BUILD SUCCESS. Covered rollback after sender debit, same-key concurrent replay, changed-payload conflict, 16 opposing transfers preserving total, and mixed simultaneous transfers/withdrawals.
- Commit: `5e0db8ec31d5882194f7bdebcc9f3d07b0a26acc`. Push blocked by unauthenticated CLI/no remote.

## Milestone 5 — completed
- Responsive HTML/CSS/ES module interface uses same-origin authenticated APIs. Registration, account onboarding, dashboard, deposit/withdrawal dialogs, reviewed transfers, paginated history, profile and logout implemented.
- `mvn -B test package`: BUILD SUCCESS, 24 tests, 0 failures/errors/skips. `node --check src/main/resources/static/app.js` passed. Official Maven 3.9.11 wrapper generated.
- Browser verified fictional users Alex Morgan and Jamie Taylor: registration/login, zero account, 12,500.50 deposit, 500.25 withdrawal, 2,000.25 transfer, sender 10,000.00 and recipient 2,000.25; histories show matching reference. Profile and logout verified. Desktop and 390px mobile layouts inspected; genuine screenshots saved in docs/screenshots.
- Native Windows server needed explicit `jdk.net.unixdomain.tmpdir` due to host socket-path failure; startup succeeded with project-local directory. Docker build initially failed due to Maven Central connection refused; network diagnosis now succeeds and retry is running.
- Commit: `21d7a80f62ac32b6f2f17e0098bb991d1d71c798`. Push blocked by CLI authentication; connector is connected but has no repository-creation capability.

## Milestone 6 — completed locally (30 October 2026)
- Added PostgreSQL immutable-audit trigger, decimal-string money responses, UTF-8 password boundary handling, correct HTTP method/media errors, frontend stale-notice cleanup, readable source formatting, non-root container health check, loopback app publishing, CI workflow, comprehensive README/API/demo/architecture/interview documentation and reusable Docker smoke script.
- Expanded suite: 29 tests initially all passed, but Windows packaging failed because the preview JVM held the target JAR open. Stopped only that preview, then `mvn -B verify` passed with 29 tests and packaging. Added two HTTP/UTF-8 regressions and reran: **31 tests, 0 failures/errors/skips, BUILD SUCCESS** (45.516 seconds). Real HTTP tests use server sockets and session cookies; concurrency tests use PostgreSQL 17 Testcontainers.
- `node --check src/main/resources/static/app.js` passed. Official wrapper `mvnw.cmd --version` verified Maven 3.9.11 / Java 21.
- First Compose build failed with Maven Central connection refused. After network recovery the retry progressed; removed unnecessary dependency:go-offline prefetch, used a Maven cache mount and rebuilt successfully. App and database both reported healthy, `/api/v1/health` returned UP.
- `scripts/smoke.ps1` against Docker passed: registration, session/CSRF, zero accounts, deposit, withdrawal, atomic transfer, same-key replay, ownership rejection, sender/recipient balances 70.00/20.25, three history rows and logout.
- Final-source `docker compose up --build --wait --wait-timeout 180` succeeded; both services healthy. Reran smoke script against that final image successfully. Queried Flyway history: V1–V5 all successful. Runtime UID verified as 10001 (non-root).
- GitHub Actions configured but not remotely executed. CLI still unauthenticated; no remote or push. User requested migration to WSL Ubuntu to use its GitHub setup. Ubuntu has Git, Java and Docker but no gh on PATH; destination /home/san_win/projects/vaultbank is unused. Windows source will be retained as a backup.


## NileBank rename and direct WSL workspace — in progress
- User renamed the product to NileBank and requested /home/san_win/NileBank as the active folder. Copied all six commits there, retained earlier copies, and created codex/nilebank-mvp.
- Verified GitHub SSH as Mr-San-Win. Existing Mr-San-Win/NileBank repository contains a main README placeholder only; origin now points to that repository. Main is unchanged.
- Renamed Java packages/application/artifact, frontend, documentation and container identity. New account/reference prefixes use NB; existing VB account numbers remain accepted for compatibility.
- Original Flyway V1-V5 files remain unchanged. V6 updates application metadata. The ignored local environment retains DB_NAME=vaultbank and Docker project nilebank to preserve existing PostgreSQL data.
- Milestone 6 commit: d91a063b72c963bb941f16d5963a3310dfbdd2a0. Native WSL verification pending; previous wrapper download was interrupted, not a passed build.

### NileBank verification completed
- Native Ubuntu Java 21 / Maven 3.9.11 verify: BUILD SUCCESS, 31 tests, 0 failures/errors/skips; executable target/nilebank-0.1.0.jar created (1 minute 13 seconds).
- WSL Docker build and readiness succeeded. Flyway V1-V6 successful; application metadata is NileBank. Account count and total funds before/after migration both 4 / 180.50 THB.
- python3 scripts/smoke.py passed against the WSL-built Docker app: auth/session/CSRF, new NB account/reference IDs, deposit, withdrawal, transfer, replay, ownership, history and logout.
- Native browser verified NileBank title, N brand mark, registration/login, zero account with NB identifier and persisted 10,000.00 simulated deposit. Genuine desktop/mobile screenshots refreshed.
- A trailing CR in a temporary shell helper caused its final empty line to exit nonzero after all Docker checks had succeeded; application build/readiness/migration/data comparisons succeeded independently. Windows refused executing a PowerShell smoke file over UNC; no security policy was changed, and the native Python check passed instead.
- Source rename is verified and ready to commit. Next: connect the existing main README history and push only codex/nilebank-mvp.

## GitHub recovery and delivery — pushed
- Repository: https://github.com/Mr-San-Win/NileBank ; active folder /home/san_win/NileBank ; branch codex/nilebank-mvp.
- SSH authentication succeeded as Mr-San-Win. Repository already existed, so no creation or additional gh login was needed.
- Rename commit 5e086c0856c3516442e36e69d6b788b33afec6c9 passed the recorded WSL/Docker/browser checks.
- Merged existing main history in ef04ce09b1ca782c458114024900e8144dad4faa. The only conflict was its original '# NileBank' README placeholder; retained the verified detailed NileBank README. Original content remains in history.
- git push --set-upstream origin codex/nilebank-mvp succeeded. Remote branch SHA verified as ef04ce09b1ca782c458114024900e8144dad4faa. Main stayed 1315d16e133b7e75a00a89a86766637e16798ebc. No force-push, deletion or main update.

| Milestone | Commit | Push result |
| --- | --- | --- |
| Foundation | 909ffe08483ed04a158671e7f1dc47ee61cc507c | Pushed in recovery branch history |
| Authentication | 4f9b70c24dbfae3937ab6d1ad36aa503e263bbfa | Pushed in recovery branch history |
| Accounts/transactions | 2e818c2bed8bbfb88360777d4334a023b4b46e19 | Pushed in recovery branch history |
| Atomic transfers | 5e0db8ec31d5882194f7bdebcc9f3d07b0a26acc | Pushed in recovery branch history |
| Frontend | 21d7a80f62ac32b6f2f17e0098bb991d1d71c798 | Pushed in recovery branch history |
| Quality/delivery | d91a063b72c963bb941f16d5963a3310dfbdd2a0 | Pushed in recovery branch history |
| NileBank rename | 5e086c0856c3516442e36e69d6b788b33afec6c9 | Pushed |
- GitHub Actions run 41007037465 was in progress when checked: https://github.com/Mr-San-Win/NileBank/actions/runs/41007037465 . No remote success claimed yet. A later documentation receipt commit records these results.

## Final CI result — successful
- GitHub Actions 41007135590 completed successfully for 61917c7848ec4ddc1f7171a13618904d50d98a57: PostgreSQL verify, JavaScript syntax, Docker image build and evidence upload all succeeded.
- Earlier source push run 41007037465 also completed successfully.
- Evidence: https://github.com/Mr-San-Win/NileBank/actions/runs/41007135590 . This final receipt changes documentation only and uses [skip ci] to avoid repeating the already-green application checks.

## Requested extensions — local development, no commits or pushes
- Owner requested currency exchange, fictional partner-bank connections and a personal finance tracker/analyzer. Existing WSL source/database remain intact; deleted GitHub repository will not be recreated without the owner's later review.
- Fixed demo rates (THB/USD/EUR), atomic parent-account locking, persisted 120-second quotes, quote replay protection, separate foreign wallets and immutable exchange history are being implemented first. Old migrations remain unchanged.
- Owner clarified that rates must come from the internet and refresh on demand. Replaced fixed runtime rates with Frankfurter v2 ECB reference data, source date and fetch timestamp. These are real daily reference rates, not intraday trading quotes. Provider failures reject new quotes without a fixed fallback. Runtime response verified from the provider; deterministic rates exist only as isolated test fixtures.
- Real-rate exchange, partner-bank and finance backend suites passed. Integrated three responsive frontend screens with provider/date refresh, quote/transfer review, wallet balances, tracker pagination, budgets, goals and explainable analysis. No commits or pushes.

## Extension verification — 2026-10-30, ready for owner testing
- Implemented real-reference-rate THB/USD/EUR wallets and exchanges, fictional Harbor/Orchid bank links and idempotent movements, and manual finance entries/budgets/monthly insights/savings goals. Flyway V7–V9 applied successfully to the Docker PostgreSQL database. Existing data was preserved.
- A resumed verification initially failed because Docker Desktop was stopped (56 attempted tests, 52 initialization errors). Started the already-installed Docker Desktop; did not skip or weaken integration tests. Subsequent final Maven verify succeeded: 56 tests, zero failures/errors/skips, BUILD SUCCESS in 53.953 seconds. Java 21, real PostgreSQL Testcontainers.
- Final `docker compose up --build --wait --wait-timeout 180` succeeded; application and database both healthy on localhost:8080. JavaScript module syntax checks and `git diff --check` passed.
- `python3 scripts/smoke_extensions.py` passed against the final image and actual internet provider. ECB publication 2026-10-30, retrieved 2026-10-30T15:28:42.861312594Z. Verified refresh fetches again, quote/execution/replay, foreign wallets, partner movements/reconnection, finance arithmetic/budgets/insights/goals, ownership and logout.
- Browser checks with a fictional profile: executed THB350 -> USD10.36 using the earlier 2026-10-29 publication; linked Harbor and moved simulated THB100 in; recorded THB20000 income and THB500 food spending, THB400 food budget (THB100 over), and THB2500 allocation toward THB10000 goal (25%). Values survived application/DB restart. Current rate screen refreshed to publication 2026-10-30. History correctly labels exchange received currency and partner top-up. Desktop and 390px mobile screens inspected; no page-level horizontal overflow observed.
- Added docs/EXTENSIONS.md, updated README and documentation pointers, formatted new Java sources, and fixed a notification refresh that hid operation references.
- Branch: feature/nilebank-enhancements. Base/latest committed SHA remains 524c85b1a2e3a14ecea0148e3f4ce845a21759d1. All extension changes are uncommitted. No staging, commit, remote creation or push performed; waiting for owner testing as requested. `.env`, dependencies and generated Python cache remain ignored.
- Limits: reference rates update by publication, not each market tick; provider outages block new quotes with no fixed fallback. All funds/partners are simulated, tracker inputs are manual, and savings allocations do not reserve money. No real-bank connector or live trading service exists.

## Authorized final repository verification — 2026-10-30
- Owner authorized final documentation, commits and publication to codex/nilebank-mvp. Created that local branch safely from the reviewed working tree; preserved existing commits and unrelated remote history.
- Re-ran complete Maven verify on Java 21/PostgreSQL Testcontainers: 56 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS in 39.997 seconds at 22:43:28 Asia/Bangkok. Both original and extension HTTP smoke scripts passed against healthy Docker containers. Real ECB publication 2026-10-30 retrieved 2026-10-30T15:44:29.104596093Z.
- Repository inventory includes Maven wrapper/pom, Java layers, migrations V1–V9, HTML/CSS/ES modules, tests, Dockerfile/Compose, CI workflow, API/demo/architecture/interview documentation and genuinely captured screenshots. CI now checks both JavaScript modules.
- Reviewed intended files: .env, .tools dependencies, target binaries and Python caches remain ignored. High-confidence secret scan inspected 85 text files with zero matches; credential-related locations were manually reviewed as environment references, Maven wrapper variables, CSRF values or fictional test examples. No .env is tracked or present in reachable Git history. No real credentials added.
- Refreshed README for all implemented features and 56-test evidence; replaced architecture guide with current database/transaction/FX design; wrote five-decision internship interview guide. Historical CI receipts belong to the previous repository and are not proof of new CI execution.
- GitHub blocker: origin git@github.com:Mr-San-Win/NileBank.git returned “Repository not found.” Windows .tools/bin/gh.exe reports no authenticated GitHub hosts; gh is not installed on WSL PATH. Connected GitHub profile is Mr-San-Win but connector repository list is empty. Asked owner for exact recreated URL and local CLI login. No successful new push or CI run claimed.
- Commit hash and any subsequent publication receipt will be recorded after the actual operation; no historical milestone commits are being fabricated.

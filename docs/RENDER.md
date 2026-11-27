# Render Free + Neon PostgreSQL

This is a low-traffic educational demo deployment, not a production banking service.
The recommended path is the prebuilt image from GitHub Actions; the source-build
form below is retained as a reference, but it is blocked when Render builders
receive Maven Central 429 responses. Neon stores persistent data;
Render's container filesystem and in-memory sessions are ephemeral. Sleeping/restarts
log users out; scheduled finance catch-up resumes when the application is awake.
Never use Render's expiring free PostgreSQL for durable demo data.

## Render form

- Name: `nilebank-demo` (actual available service hostname may differ).
- Runtime: Docker; branch: `main` after this configuration is reviewed and merged.
- Region: Singapore, matching the Neon project.
- Root Directory: blank; Dockerfile Path: `backend/Dockerfile`.
- Docker Build Context Directory, if shown: `.`.
- Compute: Free ($0/month).
- Advanced Health Check Path: `/api/v1/health`.
- Auto-deploy: Off initially, so an unrelated push does not deploy automatically.

## Required environment variables

Enter secrets directly into Render's private environment editor. Do not commit,
screenshot, paste into chat or put credentials in the JDBC URL.

| Key | Value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `render` |
| `APP_PUBLIC_URL` | Omit for first deployment: Render automatically supplies `RENDER_EXTERNAL_URL`, which the profile uses. Set explicitly only for a verified custom domain. |
| `DB_URL` | `jdbc:postgresql://DIRECT_NEON_HOST:5432/nilebank?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` |
| `DB_USER` | The selected Neon database role, currently `nilebank_owner` |
| `DB_PASSWORD` | Its private provider-generated password. Render accepts at least 16 characters only for a Neon hostname with verify-full and Java certificate validation; other deployment configurations retain the 24-character policy. Never paste it into chat. |
| `JAVA_TOOL_OPTIONS` | `-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError` |
| `GOOGLE_LOGIN_ENABLED` | `false` |
| `GOOGLE_REAUTH_ENABLED` | `false` |
| `RECOVERY_ENABLED` | `false` |
| `EMAIL_VERIFICATION_ENABLED` | `false` |
| `MFA_ENABLED` | `false` |

The Render profile reads Render's automatic `PORT` (default 10000); do not create
PORT manually. It applies the existing production validation, so secure cookies, canonical
HTTPS validation and structured logging remain enabled. No TRUSTED_PROXY_REGEX
variable is needed for this profile. Remove the APP_PUBLIC_URL environment row
entirely for the automatic URL; an explicitly empty value overrides the fallback
and is correctly rejected.

In Neon Connect, turn **Connection pooling off** to obtain the direct hostname
(without `-pooler`). Keep the production branch and `nilebank` database selected.
Use a small direct Hikari pool for this single JVM and its Flyway migrations.
The JDBC URL above uses Java's trusted certificate authorities and verifies the
server hostname. Do not weaken TLS to make connection errors disappear. The
Neon connection string itself is not a JDBC URL; extract only its hostname and
enter the username/password in their separate Render settings.

No actual Neon credentials are present in this repository. Actual Neon certificate
validation, role permissions and migrations still require a connection test after
configuration. Flyway applies the existing V1–V18 migrations to the new database;
never copy balances from a workstation or reset an existing database to fix startup.

## TLS and client IP boundary

Render documents that its edge redirects public HTTP to HTTPS and terminates TLS
before forwarding HTTP to the container. RenderConfig therefore marks the internal
connector as HTTPS/secure with external port 443. Do not use this profile on a
standalone publicly exposed HTTP server: it does not implement TLS itself.

We deliberately trust no forwarded client addresses until the real ingress peers
are verified. Spoofed forwarded proto/host/IP headers cannot change that policy.
The application IP limiter therefore groups users by the actual socket proxy peer;
it is conservative and may return 429 to unrelated demo visitors sharing ingress.
Do not remove the limiter or trust all private addresses as a workaround. Staging
must establish the real proxy chain before enabling accurate per-client limits.
Public URLs and Google callbacks must remain explicitly canonical, not inferred
from arbitrary forwarded headers. Google remains disabled in this first stage.

## Staged verification

First confirm CI on the merged configuration, then deploy manually on the free
Render URL. Confirm health, all V1–V18 migrations and no secret-bearing startup
errors. Test signup/login/session cookies/CSRF, money operations, savings, exchange,
finance tracking and logout. Verify cold-start recovery and resource usage after
idle; a successful local 512 MB test does not prove Render's 0.1-CPU performance.

Configure real Google and SMTP only in separate tested steps with the final domain
and credentials. Keep MFA off until its encryption keys are securely managed; never
remove keys if existing users already enrolled. Hosting this empty new database
with these optional features off preserves password authentication.

Use the free URL before purchasing a domain. After custom-domain DNS and TLS are
verified, update APP_PUBLIC_URL (and enabled callback/recovery settings) and retest.
Backups, off-host monitoring, public privacy/retention ownership and abuse review
remain requirements before broad public registration. This document does not
claim public deployment or a production security certification.

References: [Render port/TLS behavior](https://render.com/docs/web-services),
[Docker builds](https://render.com/docs/docker),
[Neon connections](https://neon.com/docs/get-started/connect-neon),
[pgJDBC TLS validation](https://jdbc.postgresql.org/documentation/ssl/).

## Executed local evidence

The updated Docker image built successfully. With a hard 512 MB container limit,
the image started and all 18 migrations applied to a fresh isolated PostgreSQL 17
database. The existing HTTP banking smoke passed through a local certificate-
validated TLS edge: sessions/CSRF, deposits/withdrawals, transfer/replay, ownership,
history and logout. No OOM kill occurred; a post-smoke sample was 280.7 MiB, which
is not a peak-memory benchmark. QA used one CPU, so this does not establish cold-
start time on Render Free's smaller CPU allocation. Actual Neon TLS and Render
network/callback behavior remain unverified.

Reproduce the container check on Linux/WSL (Docker and OpenSSL required):

```sh
docker build -f backend/Dockerfile -t nilebank:render-local .
python3 scripts/verify_render.py
```

The script creates disposable QA credentials/certificates, its own container network
and fresh database, then removes those resources. It does not use existing
application data or actual Neon credentials.

Final clean Maven verification passed: **261 tests, zero failures/errors/skips**
(including three new Render HTTP/profile tests on PostgreSQL 17). Both production
and Render HTTP suites also passed in isolation. A prior run encountered a transient
container database connection refusal; no test was removed or weakened.

Neon enforces password entropy upstream; the narrow Render/verified-Neon exception
accepts provider-generated credentials without inventing a new password format.
Never edit or pad the issued password. If exposed, rotate in Neon and update Render.
An explicit APP_PUBLIC_URL still takes precedence over the platform-supplied URL.
Sources: [Neon password policy](https://neon.com/docs/security/security-overview),
[Render platform URL](https://render.com/docs/environment-variables).

Bootstrap compatibility follow-up: full clean Maven verification passed with
**265 tests, zero failures/errors/skips**, including the managed-credential boundary
and automatic platform URL tests. No dependencies or migrations changed.

## Prebuilt image delivery (recommended after repeated Maven Central 429)

Do not keep retrying the rate-limited Render Maven build, clear its cache, change
Spring Boot versions or disable TLS. GitHub Actions uses its Maven dependency
cache, runs the full verification suite, packages the frontend in the JAR and
builds/tests a runtime-only image. The runtime Docker build does not run Maven.
The separate publish job runs only after successful verification on main (push
or manually dispatched main workflow), with package-write permission limited to
that job. Pull requests never publish images or receive registry credentials.
The existing multistage Dockerfile remains available for local source builds.

After merging this workflow, wait for both verify and publish jobs to succeed.
The expected package is `ghcr.io/mr-san-win/nilebank`; confirm its actual output
and SHA tag/digest before use. A newly published GHCR package may be private.
In GitHub's package settings, the owner must deliberately make this educational
application image Public before anonymous Render pulls work. Do not create or
paste a personal access token as a shortcut. The image contains only the runtime
and packaged application, not source caches, .env files, database contents or
runtime credentials. Review the image/package before changing visibility.

In Render, choose Existing Image for a new service, or inspect the existing
service's source settings for a supported switch. Do not delete the existing
service or create a replacement automatically. Switching backing sources can
trigger a deployment, so do this only when ready. Use the verified image digest
`ghcr.io/mr-san-win/nilebank@sha256:ACTUAL_DIGEST_FROM_PUBLISH` rather than guessing a
digest or silently tracking mutable main. If the dashboard does not support a
source-type switch, create a separate Free image-backed service manually, retain
the failed service until verification, and copy private environment settings
directly between Render editors without exposing them.

Keep the same Singapore region, Free plan, health path and environment variables.
No Root Directory, Dockerfile Path or Maven build command is needed for an
image-backed service. APP_PUBLIC_URL remains absent: Render's assigned HTTPS URL
is automatic. Actual registry publication, visibility and Render startup must be
verified externally; local image tests alone do not establish deployment success.

Reproduce the runtime-only packaging from a successfully verified JAR:

```sh
mkdir -p .tools/release-image
cp backend/target/nilebank-0.1.0.jar .tools/release-image/
docker build -f backend/Dockerfile.runtime -t nilebank:render-local .tools/release-image
python3 scripts/verify_render.py
```

This eliminates dependency downloads from Render's deployment path and avoids
rebuilding the application after tests. It is not a guarantee that all external
registries or GitHub build downloads can never fail. No deploy hook, cloud API
credential or automatic public deployment is added.

Runtime-only delivery local validation: official actionlint v1.7.12 passed;
Docker image build and the isolated 512 MB HTTPS banking smoke passed, including
all 18 migrations. Sample memory was 271.8 MiB (not a peak benchmark). Java source,
dependencies and migrations are unchanged; the last full Maven run had 265 passing
tests. The new workflow itself still requires a real GitHub run; registry publication
and anonymous pulls are not claimed as verified until that succeeds.

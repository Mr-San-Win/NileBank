# Deployment preparation and operating runbook

This is an educational simulator. No public deployment has been performed. Local
verification does not establish production readiness. Do not enter real banking
information. Google login, if enabled, necessarily stores a real Google name/email.

## Profiles and network boundaries

Local development uses `compose.yaml`, a loopback application port and a private
PostgreSQL service. `compose.production.yaml` is a separate single-host template:

- Java runs as UID 10001 with a read-only root filesystem, writable bounded `/tmp`,
  no Linux capabilities, no-new-privileges, CPU/memory/process limits and bounded logs.
- PostgreSQL has no published port. A persistent named volume holds database data.
- The app binds only to host loopback. An operator-managed HTTPS reverse proxy is
  required in front of it. No proxy, certificate, DNS record or cloud server is provisioned.
- The `prod` Spring profile forces Secure cookies, native Tomcat forwarded-header
  handling, graceful shutdown, bounded connection pooling and ECS JSON console logs.
- Startup validates a canonical HTTPS `APP_PUBLIC_URL`, secure cookies, PostgreSQL
  settings, a sufficiently long database password and explicit proxy expression.
  Google callbacks must exactly match that origin when enabled.

The expression validator checks presence/syntax and rejects common wildcard forms;
it cannot prove an arbitrary regular expression describes only trusted machines.
Review `TRUSTED_PROXY_REGEX` against the **actual TCP peer addresses** observed by
Tomcat. Do not trust every private network or use wildcard expressions. A Docker
bridge gateway may be the peer for a host proxy; verify it rather than copying a
workstation address. The proxy must replace untrusted forwarding headers and supply
`X-Forwarded-For` and `X-Forwarded-Proto`. Firewall port 8080 from external access.
Test spoofed forwarding headers from untrusted network paths before public use.

Reference: [Spring Boot 3.5 proxy configuration](https://docs.spring.io/spring-boot/3.5/how-to/webserver.html)
and [structured logging](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html).

## External setup still required

1. Obtain hosting, DNS and TLS separately. No purchases are authorized here.
2. Store environment values in a protected host environment or secret manager,
   never Git/chat/shell history. Use a strong distinct `DB_PASSWORD` and
   `DB_ADMIN_PASSWORD` (at least 24 characters), a non-bootstrap `DB_USER`,
   `APP_PUBLIC_URL` and a restricted `TRUSTED_PROXY_REGEX`.
3. Keep Google disabled unless its client, consent screen, exact HTTPS redirect
   and secret rotation are configured. See authentication documentation.
4. Review resource limits against measured usage. One application instance is the
   supported template: sessions and abuse buckets are in memory. Restarts log users
   out. Multiple independent instances require an approved session/ingress design.
5. Set ingress authentication/body/request limits, TLS policy and security monitoring.
   The bounded in-process IP limiter is not an Internet abuse-defense service.
6. Establish backup encryption, off-host storage, restore drills, log access controls,
   incident handling and privacy policy before accepting public registration.

After those steps and technical approval, the operator can validate the template:

```sh
docker compose -f compose.production.yaml --env-file .env.production config --quiet
# Starting on a public host requires deployment approval; this runbook is not approval.
docker compose -f compose.production.yaml --env-file .env.production up --build -d --wait
```

`.env.production` is ignored. Blank example placeholders intentionally cannot start
this template. Never print interpolated Compose configuration containing secrets;
use `config --quiet`. Do not enable shell tracing around secret handling.

## Database roles and initialization

On a **new** template volume only, `infra/postgres/init-app-role.sh` creates the
application login as NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOREPLICATION, grants database
connect and assigns the public schema to it. SQL identifiers/passwords use psql
variables plus server-side quoting; values are not echoed. The separate bootstrap
role remains privileged inside PostgreSQL and is never supplied to the app.

The app role owns its schema because startup Flyway applies migrations. This allows
it to alter audit triggers; history is not tamper-proof against a compromised schema
owner. A separately privileged migration job and restricted DML runtime role remain
an improvement before a stronger security claim. Role-name validation alone cannot
prove privileges for an externally provisioned database: verify `pg_roles` explicitly.

Initialization scripts do not run on an existing volume. Do not reset that volume to
change roles/passwords. Plan and verify grants with an administrator. Environment
changes do not rotate an existing PostgreSQL password; use an authenticated admin
session and psql's interactive `\password` command, update the secret store, restart
only affected clients, and confirm connectivity. Rotate Google secrets in the provider
console and secret store without placing them in version control.

## Migration and release sequence

Never edit applied V1–V15 migrations or enable Flyway clean on persistent data.
Take a backup, restore to a separate staging database, run migrations there and
verify representative login and financial operations. Flyway validates checksums.
For exchange settlement/provider changes, follow the offline quote-drain procedure
in [FX rollout](FX_ROLLOUT.md); stop new quotes and wait for old executable quotes to
expire before replacement. Executed quote replay remains valid.

Graceful shutdown allows up to 20 seconds for requests, with a 30-second container
stop grace period. PostgreSQL rolls back interrupted uncommitted transactions.
After an uncertain response, use the same retry key or inspect history. Do not
assume a stopped HTTP response proves that a transaction failed.

Database rollback is not automatic: restore only under an approved outage/recovery
plan into a fresh database, validate it and switch deliberately. Rolling an old app
back over a newer schema requires compatibility review. Never delete financial data
or rewrite history as a deployment workaround.

## Backup and restore

Use PostgreSQL 17 client tools. `pg_dump -Fc --no-owner --no-acl` captures a consistent
logical snapshot; roles and external configuration require separate protected records.
Do not commit dumps. Encrypt backups at rest and in transit; restrict access.

With credentials already supplied by the protected container environment, these
commands avoid passwords on command lines. Substitute only verified DB/role names:

```sh
umask 077
docker compose -f compose.production.yaml --env-file .env.production exec -T db \
  sh -c 'pg_dump -U "$DB_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-acl' > backup.dump
```

Restore into a **new empty database**, never the running production database. An
administrator creates the new database with the appropriate owner. Run `pg_restore`
with `--no-owner --no-acl --exit-on-error`, validate migration history, user/account
counts, balances, immutable audit triggers and representative app journeys before
considering a switchover. Count equality alone is not a complete integrity check.
Test storage loss and restores periodically; define recovery objectives from measured
backup/restore times rather than inventing guarantees. Daily encrypted backups with
an operator-approved retention period are a starting policy, not a verified SLA.

## Health, logging and monitoring

`GET /api/v1/health` queries PostgreSQL and returns controlled status without secrets.
Container health checks use it. Monitor external HTTPS availability separately from
container liveness, plus database disk capacity, backup age, failed restores,
container restarts, resource pressure, repeated auth failures and provider failures.
No metrics backend or alert delivery has been provisioned.

Security events log allowlisted event names and internal UUIDs, not passwords,
emails, OAuth codes or tokens. Unexpected API errors omit stack traces/details.
Structured logs do not automatically redact arbitrary values from third-party
libraries. Keep SQL bind/protocol/request-body debug logging off. Never put credentials in
`DB_URL`; datasource URLs may appear in startup logs. Use separate protected
username/password settings. Configure the proxy
not to retain OAuth callback queries or recovery tokens. Restrict log access and
approve retention; review database logs separately for PII.

## Privacy and remaining release gates

Read [privacy/retention policy](PRIVACY.md). Optional password recovery is default-off and requires verified real SMTP/inbox
delivery before advertising it. Optional profile email confirmation is implemented; mandatory public-registration enforcement requires a deliberate rollout policy. TOTP MFA is optional and requires the key
management and rollout checks in the MFA runbook. Public users need clear account recovery
limitations, support and a deletion policy. Real Google consent, trusted TLS ingress,
abuse controls, encrypted off-host backups, operational monitoring and privacy
handling still require environment verification and ownership. The template and local
tests do not make this production banking software.

## Optional recovery delivery

Follow [password recovery](PASSWORD_RECOVERY.md) before setting `RECOVERY_ENABLED`.
The production profile requires a matching canonical HTTPS recovery origin,
authenticated STARTTLS and certificate verification. Local SMTP/browser tests do not
prove production inbox delivery. Keep credentials in the protected environment;
configure sender DNS and mail quotas externally. V16 is additive; existing users and
financial records are unchanged.

## Authenticator keys

Follow [MFA key operations](MFA.md) before enabling enrollment. Keep encryption keys
outside Git and database backups; retain the keys needed by all stored key IDs.
Disabling new enrollment does not bypass existing factors. Missing keys fail closed
for enrolled sign-ins. Rehearse key/database restoration together and monitor clock
synchronization. Google-only enrollment and assisted lost-factor recovery are not
implemented. No real production keys were created by this workstream.

See [email confirmation](EMAIL_VERIFICATION.md) before enabling EMAIL_VERIFICATION_ENABLED.
Existing accounts are not automatically blocked for lacking confirmation.

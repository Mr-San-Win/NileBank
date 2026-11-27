# Operational preparation

These tools do not deploy NileBank. Hosting, DNS, certificates, real Google/SMTP
activation, off-host storage and alert delivery remain external requirements.

## HTTPS ingress

`infra/nginx/nilebank.conf` is a **host-installed Nginx** HTTP-context template.
Replace `nilebank.example` everywhere and supply the certificate chain/private key
at its configured paths using restricted root-owned files. Include the template
from the Nginx HTTP context, run `nginx -t`, then reload the host service.
Certificate issuance/renewal remains an operator responsibility. Its loopback
upstream assumes host Nginx and production Compose bound to host 127.0.0.1:8080;
it is not a container deployment configuration.

Forwarded headers are overwritten, redirects use a fixed hostname, TLS 1.2/1.3
is enabled and authentication routes receive an ingress limit (429). Access logs
omit query strings. Error logging is disabled because errors can contain OAuth
callback arguments; use readiness monitoring and redacted application security
events, and review this observability tradeoff before deployment. Verify the actual
TCP proxy peer seen by the application and configure its exact address in
`TRUSTED_PROXY_REGEX`. Never trust arbitrary client-supplied forwarded headers.
Do not publish PostgreSQL or the application port on a public interface.

Local syntax verification is not proof of real-host TLS, proxy trust, secure-cookie
behavior or Google callback configuration. Those require staging verification.

## Encrypted backups and recovery

Requires Linux/WSL, Docker Compose and GnuPG. Import and trust the operator-managed
backup encryption public key in the backup account's GPG keyring; keep its private
key and recovery material separately from the application server. Run from the
repository root with the production Compose environment securely configured.

```sh
mkdir -p backups
sh scripts/backup_database.sh FULL_TRUSTED_RECIPIENT_FINGERPRINT backups/nilebank-YYYYMMDD-HHMM.gpg
```

Use a unique filename for every run. The script streams a consistent custom-format
PostgreSQL dump into GPG without writing plaintext to disk. It checks both process
results and publishes encrypted bytes atomically with owner-only permissions,
refusing existing destinations. It never creates keys or bypasses key trust.
Check key expiry, available disk space and the exit status. Coordinate backups
with migrations; a backup during migration is not a supported recovery point.
Copy encrypted backups off-host and verify transfer success. No off-host account,
retention schedule, automatic upload or alert destination is configured here.

Restore only into an explicitly selected **fresh isolated database**. Decrypt into
an owner-only temporary directory and require GPG success/integrity verification
before running `pg_restore --exit-on-error` against the new database. Do not pipe
unverified plaintext into a database: GPG can emit bytes before detecting failed
integrity. Verify Flyway history, audit counts and representative balances before
any recovery decision. Remove temporary plaintext according to storage policy.
Never use `--clean` or overwrite a live database automatically. Preserve the matching
application version and original MFA encryption key ring separately; a database
backup alone cannot recover encrypted MFA seeds without their keys.

## Monitoring

```sh
python3 scripts/check_health.py https://your-approved-host.example
```

Returns JSON UP/DOWN and exit status 0/1. It checks the existing PostgreSQL-backed
health endpoint with TLS certificate validation, no redirects, bounded response
size and timeout. `--ca-file` accepts an operator-managed CA; no insecure bypass
is provided. It logs no response bodies or sensitive request URLs. Configure an
external monitor or separate-host timer plus an alert recipient before deployment;
this tool does not create schedules or send messages. A probe cannot verify every
banking workflow.

## Reproducible checks

```sh
python3 -m unittest discover -s scripts -p test_operations.py
python3 scripts/verify_operations.py
```

The integration script requires Docker, GnuPG, OpenSSL and the PostgreSQL/Nginx
images. It uses disposable QA keys/certificates and a new uniquely named database
container; existing application databases are untouched. It verifies encrypted
backup/restore, permissions, overwrite refusal, corrupted ciphertext rejection,
failed-dump cleanup, real trusted HTTPS and rejection of an untrusted certificate,
plus Nginx syntax with certificates. It removes its container and temporary files.
Nginx syntax uses the mutable `nginx:stable-alpine` tag; actual provisioning should
record an approved digest. This is not a public deployment or disaster-recovery
certification.

## Observed results — 23 November 2026

Full Maven verification: 258 passed, zero failures/errors/skips. Six operational
unit tests passed. The isolated backup, TLS and Nginx checks passed. No banking
logic, dependencies or database migrations changed in this operational milestone.

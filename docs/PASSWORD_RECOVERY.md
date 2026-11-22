# Optional SMTP password recovery

Recovery is disabled by default. It works only for existing password profiles;
it never creates a password for a Google-only user, links accounts by email or
removes another authentication factor. No reset link is returned by a public API.

## Flow and persistence

`POST /api/v1/auth/recovery/request` accepts `{ "email": "..." }` with CSRF and
returns the same 202 response whether the address exists, belongs to a Google-only
profile, is throttled, delivery fails or the feature is disabled. Lookup/delivery
runs on a bounded two-worker/64-item in-memory queue; request processing does not
wait for SMTP. Queue rejection is logged as a redacted event. Restarting may lose
queued requests; users can request another email. This is not a durable mail outbox.

Under the user's database lock, issuance permits at most one new link per minute,
invalidates previous outstanding links and generates 32 cryptographically random
bytes. V16 persists only the SHA-256 token hash, user UUID, credential version,
creation/expiry/delivery/consumption times. Raw tokens exist only in working memory
and the email. Tokens expire after 15 minutes. A failed SMTP submission leaves an
undelivered, unusable token; it does not change the password or lock the profile.

The email uses the configured canonical origin, never a request Host header. Its
link is `/#reset=<token>`: fragments do not go to the HTTP server. The frontend
captures and immediately removes the fragment, including navigation in an already
open tab. The token remains only in module memory, never browser storage. Responses
set a no-referrer policy. Email providers/browser extensions can still see links;
never share them or enable protocol/body logging.

`POST /api/v1/auth/recovery/reset` accepts `{ "token": "...", "password": "..." }`
with CSRF. It locks the user, verifies delivery/expiry/consumption and credential
version, validates password policy, BCrypt-hashes the replacement, consumes all
outstanding tokens and increments credential version in one transaction. Concurrent
consumption has one winner. Existing sessions become invalid on their next request;
the current reset session is invalidated. Reset does not log in automatically.
Internal UUID, linked Google identity, profiles and financial records are preserved.
A separate password change/link/unlink invalidates old reset links through the version.

## Configuration

Set only in an ignored environment or secret manager:

- `RECOVERY_ENABLED=true`
- `RECOVERY_BASE_URL`: canonical origin, normally your HTTPS application URL.
- `RECOVERY_FROM`: authorized sender address without whitespace/control characters.
- `RECOVERY_SMTP_HOST`, `RECOVERY_SMTP_PORT` (default 587).
- `RECOVERY_SMTP_USER`, `RECOVERY_SMTP_PASSWORD` when authentication is required.
- `RECOVERY_SMTP_STARTTLS=true` (default): STARTTLS is required, certificates and
  server identity are verified, and connection/read/write timeouts are five seconds.

Plain SMTP is permitted only for a loopback development/test sink. The production
profile requires HTTPS matching `APP_PUBLIC_URL`, STARTTLS and SMTP credentials.
There is no certificate-trust override or silent TLS downgrade. Implicit TLS on
port 465 is not implemented; use a STARTTLS-capable submission service.

Docker localhost refers to the app container, not the host. Configure a reachable
TLS mail service for Compose; do not disable TLS for a remote hostname to work around
network configuration. The `.env.example` contains blank placeholders only.

SMTP acceptance is not guaranteed inbox delivery. Before public use, configure and
verify the sender/domain, provider policy, STARTTLS/certificate path, SPF/DKIM/DMARC,
actual inbox delivery, abuse monitoring and credential rotation. No production email
credentials were created or real user emails sent during development.

## Abuse, failures and retention

Recovery endpoints share the bounded IP authentication limiter (60/minute) and CSRF
policy. Issuance also has a database-backed per-profile one-minute cooldown; repeated
requests during it do not invalidate the delivered link. Distributed ingress limits
and mail quota controls remain required. Generic responses reduce enumeration but
do not constitute a formal timing-side-channel proof.

Security events contain an allowlisted name and internal UUID or anonymous marker,
never email addresses, token/password values or SMTP exception details. Infrastructure
mail/proxy logs require separate review. Expiry and session revocation are enforced
at use time. Automatic reset-token-row retention cleanup is not implemented; an
operator-approved policy should remove expired authentication metadata separately
from immutable financial history. Dumps remain sensitive even though raw tokens are
not stored.

## Verification

Focused PostgreSQL tests exercise real local SMTP, token hashing/expiry/tampering,
single use and concurrent consumption, changed credentials, rejected passwords,
generic request responses, delivery failure, CSRF, session revocation, log redaction
and preservation of UUID/Google/account/history. Separate tests enforce default-off
behavior, configuration safety and failure when a server does not offer required TLS.
The isolated Chromium walkthrough covers email request, same-tab reset navigation,
fragment removal, new-password login and revocation of another browser session.

See [testing guide](TESTING.md) for current totals/commands. This is not email
verification, MFA recovery or an account-linking shortcut. Those have separate policies.

Design references: [OWASP forgot-password guidance](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html)
and [Spring Boot mail support](https://docs.spring.io/spring-boot/3.5/reference/io/email.html).

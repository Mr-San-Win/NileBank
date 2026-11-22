# Account security

## Implemented controls

`GET /api/v1/auth/security` reports password/Google method availability without
revealing hashes or subjects. The security settings screen supports password changes,
explicit Google linking and unlinking. Optional SMTP password recovery is implemented but disabled by default. MFA is not implemented.

`POST /api/v1/auth/security/google/link` requires an authenticated user, CSRF and
current-password proof. It creates a server-session intent valid for five minutes.
The standard OAuth resolver consumes that intent once and binds it to the stored
authorization request. Google account selection is requested. The verified callback
checks the initiating UUID and intent expiry, then locks the user and binds the
validated issuer/subject transactionally. Email is never used to choose a target
user. Existing bindings cannot be stolen, and one Google identity per user remains.

`POST /api/v1/auth/security/google/unlink` requires password proof and CSRF. An
external-only user cannot remove their last method. Unlinking signs this session out.

`POST /api/v1/auth/security/password` accepts currentPassword/newPassword. It applies
the same BCrypt byte limit and minimum length as registration and rejects reuse.
No unauthenticated endpoint creates a local password for a Google-only account.

## Session revocation

V11 adds a nonnegative credential version, initially zero, without changing existing
user IDs, hashes or financial records. Password and OIDC principals capture their
version during authentication. Linking, unlinking and password changes increment it
while locking the user. Each authenticated request checks the stored version and
rejects stale sessions. Other instances observe the same database version. Already
in-flight requests are not cancelled. Public health probes do not depend on a user.
Legacy sessions are version zero; once credentials change, those sessions also fail.

The callback converter resolves identity only after Spring's code/token/UserInfo
validation. Normal Google login rechecks its binding under the user lock before
capturing the version, closing the unlink-versus-login race. A password login keeps
the version read with its verified credential, not a later refreshed version.

## Abuse controls and logging

Authentication routes have a bounded, per-process limit of 60 requests per servlet
remote address per minute, with at most 10,000 address buckets. Excess requests get
429/Retry-After. Untrusted X-Forwarded-For is not an identity key. This is a basic
single-instance control: production needs trusted ingress limits for aggregate and
distributed attacks; NAT users share the local limit, and fixed windows permit bursts.

Security events use allowlisted event names and internal UUIDs only. They never
receive passwords, emails, claims, codes, cookies or tokens. Transactional change
events are emitted after commit. Unexpected application errors log the exception
class only, not throwable messages/stacks. Hibernate SQL exception DETAIL logging
is suppressed because it can contain profile values. Restrict and retain database,
proxy and application logs deliberately; infrastructure logs require their own review.

## Password recovery

Optional SMTP recovery uses generic asynchronous requests, hashed 15-minute single-use
links, a per-profile issuance cooldown, CSRF and existing credential-version revocation.
It does not attach a password to a Google-only user. See [password recovery](PASSWORD_RECOVERY.md)
for configuration, delivery boundaries, concurrency and tests. Real production
SMTP/inbox delivery remains an operator setup requirement. MFA is not reset by email.

## MFA assessment (not implemented)

TOTP fits the monolith, but requires encrypted seed storage/key management, confirmed
enrollment, hashed recovery codes, replay prevention and enforced pending-authentication
states across both password and OIDC flows. The current generic authenticated rule
must never admit a partially verified MFA principal. No partial enrollment or UI claim
is shipped. MFA remains a release limitation, not a supported feature.

## Verification

The original account-security checkpoint passed 132 tests on PostgreSQL 17.11 with V1-V11. Tests cover
proof/CSRF, stale intents, unique and concurrent binding, last-method protection,
password compatibility, current/other-session revocation, actual signed-provider
link callbacks, log redaction, rate-limit expiry/spoofing/bounds and all prior regressions.

The optional recovery expansion passed the complete **215-test** suite with zero
failures/errors/skips, plus actual SMTP/browser reset and old-session revocation.

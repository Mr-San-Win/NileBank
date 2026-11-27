# Profile email confirmation

Optional email confirmation is disabled by default. It records that a confirmation
link was delivered to and used for the profile's stored email. It does not sign in,
link identities, add a local password, remove MFA or move simulated money.

## Configuration and compatibility

Set `EMAIL_VERIFICATION_ENABLED=true` only after configuring the protected SMTP
settings documented in [password recovery](PASSWORD_RECOVERY.md). Verification can
be enabled while password recovery is disabled; either enabled email feature
requires a valid canonical origin/sender and secure delivery configuration. Production
requires authenticated STARTTLS and an HTTPS origin matching APP_PUBLIC_URL.

V18 adds nullable `app_users.email_verified_at` and a purpose-specific token table.
Existing users, passwords, IDs and financial records remain unchanged. Existing
login/banking access is preserved: this milestone does **not** introduce a mandatory
verification gate or silently lock legacy users out. A policy requiring verification
for future public registrations needs an explicit rollout decision. Google claim
`email_verified` is distinct from this NileBank confirmation record; external identity
resolution continues by validated issuer/subject and never links by matching email.

## Endpoints and browser flow

- `GET /api/v1/auth/security/email`: full-user session required; returns available,
  verified and verifiedAt. No arbitrary user ID is accepted.
- `POST /api/v1/auth/security/email/request`: full-user session and CSRF required;
  takes no recipient parameter and returns a generic 202. Only the profile's stored
  address can receive the link. Pending MFA sessions cannot call either endpoint.
- `POST /api/v1/auth/email/confirm`: CSRF required; `{ "token": "..." }`. A valid
  delivered link returns 204, marks the target email, consumes outstanding links and
  invalidates the caller session. It never creates a signed-in session.

The email link uses the configured origin and `/#verify=<token>`. The browser captures
and immediately removes the fragment, including same-tab navigation, then requires
an explicit confirmation click. Tokens remain in module memory only. The confirmation
screen explains that no account linking, password reset or money movement occurs.
Security settings show the confirmed status after normal sign-in.

## Token integrity and abuse controls

Tokens contain 32 cryptographically random bytes. Only SHA-256 hashes are stored;
raw links are neither API responses nor application logs. Each link expires after
24 hours and is bound to the internal UUID, exact email snapshot and credential
version. Password or authentication-method changes invalidate older links. A user
row lock makes confirmation/consumption atomic; simultaneous use has one winner.
Recovery and confirmation have separate tables and cannot substitute for each other.

A bounded one-worker/32-item in-memory queue handles SMTP outside HTTP processing.
A database-backed one-minute per-profile issuance cooldown limits mail spam without
invalidating the prior link during the cooldown. SMTP rejection leaves an unusable
undelivered record and does not confirm the address. No queue durability/inbox delivery
guarantee is claimed. Existing authentication IP limits also cover confirmation.
Raw SMTP/body/exception logging must remain disabled; infrastructure logs and quotas
need their own operational review.

No automatic token-row retention cleanup is implemented. Expiry is enforced at use
time; establish an approved retention job for expired authentication metadata without
deleting financial history. Public sender DNS/inbox deliverability and privacy policy
remain operator responsibilities. No real mail credentials or real user emails were
used in local verification.

## Verification

PostgreSQL/SMTP tests cover delivered links, hashing, expiry, credential revocation,
email snapshot mismatch, purpose substitution, concurrent use, failed delivery,
authentication/ownership/CSRF, no automatic login, default-off compatibility and
Google-only password safety. The browser walkthrough also covers actual email
request, same-tab fragment handling, confirmation and the displayed verified state.
See [implementation status](IMPLEMENTATION_STATUS.md) for executed totals.

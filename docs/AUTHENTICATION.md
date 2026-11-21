# Authentication

NileBank uses server-side sessions and CSRF protection. Password login keeps the
internal user UUID as its principal name. BCrypt cost 12 protects local passwords.
Cookies are HTTP-only, SameSite=Lax and configurable as Secure for HTTPS.

## External identity foundation

V10 adds `auth_identities`: a user foreign key, provider, validated issuer, stable
provider subject and creation time. Case-sensitive `(issuer, provider_subject)`
uniqueness prevents duplicate identities; `(user_id, provider)` limits users to one
Google identity. Existing user UUIDs, password hashes and financial references remain.
Google-only users have a null password hash and cannot authenticate by password.

The internal provisioning service accepts only `ValidatedExternalIdentity`, never
an HTTP claims DTO. The OIDC adapter validates the actual protocol and
token before constructing it. Claim-shape validation alone is not authentication.

Provisioning owns a REQUIRES_NEW / READ_COMMITTED transaction that creates a user
and identity together. Database conflicts roll back fully before a fresh transaction
looks up the issuer/subject winner. Email equality never resolves or links a user.
Existing profiles are not overwritten. Invoke this as a standalone authentication
operation, not as part of a financial transaction.

An isolated V9-to-V10 upgrade test preserves a populated user/account/history fixture.
The foundation baseline is 97 passing tests, including 17 identity integration tests.
The foundation itself does not implement callbacks or linking; the OIDC layer below adds login only.

## Rollout compatibility

Deploy code that understands nullable password hashes before enabling external-user
creation. Once external-only users exist, old binaries that assume a password hash
are not safe rollback targets. Disable external login and use a compatible build or
forward fix; do not delete users or identity rows to restore an old binary.

## Optional Google OpenID Connect login

Google login is disabled by default. Password login and all banking features work
without Google credentials or Google connectivity. `GET /api/v1/auth/options` returns
only the public `googleEnabled` flag; the frontend displays the link when enabled.

When enabled, Spring Security OAuth2 Client implements Authorization Code Flow.
Google discovery is pinned to `https://accounts.google.com`; startup needs provider
metadata connectivity. Client registration requires S256 PKCE. Spring stores state,
nonce and verifier server-side in the session, exchanges the code on the backend,
and validates the ID token's signature/JWKS, issuer, audience, authorized party,
timestamps and nonce. OidcUserService checks UserInfo subject consistency.

The application delegates to that validated OIDC service before constructing its
internal identity input. Its principal adapter keeps Google's subject intact but
returns the internal UUID from `getName()`, preserving existing ownership checks.
A first-time validated Google identity creates a passwordless demo profile, not a
bank account. Existing identities retain their original local profile. Unknown
subjects whose email is already registered fail; they never inherit that account.

Both login methods rotate the session ID and clear old CSRF state. The browser
redirects to `/`, obtains a fresh CSRF token and requests `/api/v1/auth/me`. API
requests still return JSON 401 instead of redirecting to Google. Success/failure
redirects are fixed local destinations; external `next` URLs are not used.
Google ID/access tokens remain server-side, never in JavaScript storage or DTOs.
Authorized-client storage is session-scoped. Local CSRF-protected logout invalidates
the session and its token data; it does not sign the user out of Google globally.

### Setup outside the repository

1. Create a Google OAuth Web application client and configure consent/branding,
   support contact and privacy information. Use distinct development and production clients.
2. Register exactly `http://localhost:8080/login/oauth2/code/google` for local use.
3. Set `GOOGLE_LOGIN_ENABLED=true`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` and
   `GOOGLE_REDIRECT_URI` in your ignored local environment. Never paste secrets into chat.
4. For production, register the exact HTTPS domain callback. Use Secure cookies and
   a trusted ingress. Keep the callback URL canonical; do not trust arbitrary Host or
   forwarded headers. The local Compose setup is not a production deployment.
5. Google configuration must be complete when enabled. There are no fake defaults.

Enabling Google shares actual Google name/email information with the simulator.
Explain this to users and define retention/deletion before public registration.
No additional Google API scopes or offline access are requested.

### Verification boundaries

`GoogleOidcTest` runs the real Spring redirect/callback/provider path against a local
fake HTTP provider with RSA-signed ID tokens and JWKS. It covers state, nonce, PKCE,
signature, expiry, issuer/audience, returning identities, email collision, replay,
session ID/CSRF rotation, banking ownership and logout. No real Google credentials
or network access are needed for these tests. This does not verify a real Google
Cloud client or a production reverse proxy.

Account linking/unlinking, recovery, MFA and abuse controls remain separate work.
Database uniqueness errors can include identity metadata in infrastructure logs;
production logging/redaction must be reviewed before exposing public registration.

Full AUTH-001 verification: 112 tests passed with zero failures/errors/skips on
PostgreSQL 17.11. This includes the 97-test foundation, 13 Google protocol tests
and two additional default-off/password-session-rotation regressions. The fake
provider performs code-verifier checks and serves signed tokens/JWKS; tests cover
unsigned/forged/expired tokens, issuer, audience, authorized party and absent nonce.
A database outage also follows the generic OAuth failure path without authentication.

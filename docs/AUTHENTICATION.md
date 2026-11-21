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
an HTTP claims DTO. The future OIDC adapter must validate the actual protocol and
token before constructing it. Claim-shape validation alone is not authentication.

Provisioning owns a REQUIRES_NEW / READ_COMMITTED transaction that creates a user
and identity together. Database conflicts roll back fully before a fresh transaction
looks up the issuer/subject winner. Email equality never resolves or links a user.
Existing profiles are not overwritten. Invoke this as a standalone authentication
operation, not as part of a financial transaction.

An isolated V9-to-V10 upgrade test preserves a populated user/account/history fixture.
The foundation baseline is 97 passing tests, including 17 identity integration tests.
No Google redirects, callbacks or account linking are included in this foundation.

## Rollout compatibility

Deploy code that understands nullable password hashes before enabling external-user
creation. Once external-only users exist, old binaries that assume a password hash
are not safe rollback targets. Disable external login and use a compatible build or
forward fix; do not delete users or identity rows to restore an old binary.

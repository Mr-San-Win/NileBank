# Recent Google verification for passwordless MFA management

Google-only profiles can enroll/manage NileBank TOTP without a fabricated local
password. This optional capability is default-off and requires Google Sign-In,
MFA encryption keys and Google's optional `auth_time` claim configured externally.
Normal Google login works without this capability or claim.

## Security flow

A full USER session and CSRF-protected POST to
`/api/v1/auth/security/google/reauthenticate` creates a five-minute server-side intent
bound to the internal UUID, credential version and current issuer/subject binding.
The response contains only the fixed local OAuth start path. Link and reauthentication
intents replace one another, never overlap. The resolver consumes the intent once
and binds it to Spring's session-stored authorization request/state/nonce/PKCE flow.

The request uses Google's documented account-selection prompt and asks for the
essential ID-token `auth_time` claim. Account selection alone is **not** considered
reauthentication. After Spring validates the code exchange, signature, issuer,
audience and nonce, the adapter requires the same existing bound subject, a still-full
initiating session, unchanged credential version and recent authenticated time.
Missing, malformed, old or unreasonably future time fails closed. A same-email different
subject cannot provision or take over an account in this flow.

Authentication time must be within five minutes and no earlier than 30 seconds
before the explicit intent. Proof expires no later than five minutes after that
actual provider authentication; callback time cannot extend an old login. Small
future clock tolerance does not remove expiration. Keep clocks synchronized.

The resulting proof lives only in the server-side OIDC principal. It survives Spring
replacing authentication details and is retained through the normal MFA challenge.
An already enrolled account still receives only MFA_PENDING authority until it proves
its existing factor. The callback returns only a fixed security-settings URL; no
user-controlled return target is accepted.

## MFA proof policy

Settings report `passwordRequired` and `googleVerified` without exposing the proof.
Password profiles still supply their current password, even when Google is linked.
Google-only profiles instead need this recent same-identity proof. MFA changes still
require the existing factor or the five-minute server-recorded MFA verification as
applicable. Enrollment/confirmation, recovery-code replacement and disable preserve
null password hashes and never remove the Google primary identity. Credential changes
invalidate proofs. No browser claims or request-body proof object are trusted.

## External configuration and limits

Set `GOOGLE_REAUTH_ENABLED=true` only after Google Sign-In is configured and the
optional `auth_time` claim is enabled for that OAuth client. Google documents the
`claims={"id_token":{"auth_time":{"essential":true}}}` request and the required
client setting. Do not assume `prompt=select_account` forces a password prompt;
NileBank validates actual authentication time independently. If Google supplies an
old session time, the user must authenticate again at Google before retrying.
No unsupported `prompt=login` assumption or callback-time fallback is used.

Google settings/consent, real claim delivery and real-browser interaction with Google
remain external verification tasks. Local tests use a signed deterministic provider
and actual Spring filters, not `oidcLogin()` shortcuts. No real Google credentials
were created or requested in chat.

Tests cover successful Google-only MFA enrollment and management, unchanged null
passwords, mandatory existing-factor completion, essential claim requests, wrong
subject/same email, missing/old/future/malformed times, expired/wrong-owner proofs,
CSRF/session requirements and unchanged normal Google/password behavior.

Provider reference: [Google OpenID Connect claims and authentication parameters](https://developers.google.com/identity/openid-connect/openid-connect).

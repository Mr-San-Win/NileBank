# Privacy, retention and simulator boundaries

NileBank handles simulated funds only. Use fictional profile and finance data for a
local demo. Optional Google authentication shares Google profile name/email and stores
a validated provider identity. Manual finance descriptions may contain personal data;
users should avoid sensitive information. No real bank credentials are requested.

Stored data includes profile/email/password hashes, external identity mappings,
simulated account/wallet/savings balances and immutable successful-operation history,
fictional partner connections, manual finance records, category/budget/recurrence
settings, and planning goals. Sessions/OAuth authorized-client data live server-side
in memory; no Google tokens are exposed to JavaScript. Session loss on restart is an
intentional single-instance limitation.

No self-service account export/deletion or retention scheduler is implemented. Manual
entries can be edited/deleted in the tracker; occurrence tombstones prevent a deleted
scheduled entry from being recreated. Archiving categories preserves historical
references. Cancelling a schedule stops future generation; it does not erase history.
Immutable banking/savings/cash audit records cannot be removed by ordinary SQL DML.

Before public registration, the operator must publish a contact and privacy notice,
select permitted data/retention, define identity verification for support requests,
provide an approved export/deletion or anonymization workflow, and address immutable
history, linked identities, logs and backup expiration together. No existing user or
financial records were deleted by this engineering work. Do not improvise deletion
by disabling audit triggers or dropping a persistent volume.

A possible policy is to minimize collected fields, restrict admin/log/backup access,
set bounded log and encrypted-backup retention, and expire abandoned demo profiles
only through an explicitly reviewed procedure. Those durations and legal obligations
require the operator's decision; this document does not claim legal compliance.

Google client secrets, database credentials, private keys, dumps and environment files
belong outside Git. If a secret is exposed, rotate it first and assess affected data;
rewriting Git history is a separate approved operation. Do not paste secrets in chat.

Optional security data now also includes hashed reset links, encrypted authenticator
seeds, hashed single-use recovery codes and factor attempt/replay metadata. Raw reset
links are delivered only by email; setup seeds/codes are shown only during explicit
setup/replacement. Preserve encryption-key access controls separately from database
backups. Factor removal deletes authentication metadata, not financial history.

Optional profile confirmation stores a confirmation timestamp, hashed email-purpose
tokens, email snapshot and delivery/expiry/consumption metadata. It does not create
a new sign-in method or automatically bind an external identity.

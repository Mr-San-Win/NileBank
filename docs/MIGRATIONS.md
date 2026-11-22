# Database migration inventory

Flyway migrations are append-only. Hibernate validates the schema; it does not
create or update it. Never modify applied files or reset persistent data to hide
checksum/upgrade problems. PostgreSQL 17 is the integration baseline.

| Version | Purpose |
| --- | --- |
| V1 | Foundation metadata |
| V2 | Internal users and normalized unique emails |
| V3 | Primary THB accounts and financial audit history |
| V4 | Internal-transfer idempotency |
| V5 | Immutable audit trigger |
| V6 | NileBank product metadata |
| V7 | Foreign wallets and persisted exchange quotes |
| V8 | Fictional partner connections and movements |
| V9 | Manual finance entries, budgets and planning goals |
| V10 | External identity mapping; nullable local password hash |
| V11 | Credential version for cross-session revocation |
| V12 | Seven wallet currencies and JPY precision constraints |
| V13 | Owned categories, recurring rules/occurrences and default-category seeding |
| V14 | Simulated savings accounts and immutable movements |
| V15 | Immutable scoped cash retry records |
| V16 | Hashed expiring password-reset tokens and credential-version binding |
| V17 | Encrypted authenticator seeds, replay/attempt state and hashed recovery codes |

V10 enforces issuer/subject identity uniqueness and one Google binding per user.
V13 preserves original category codes and existing finance records, backfills owner
categories and seeds categories for new users. Occurrence tombstones survive manual
entry deletion. V14 extends audit transaction types without changing previous rows;
V15 keeps cash keys separate from transfer keys. Existing V1–V9 files remain unchanged.

The suite applies the current schema to real PostgreSQL and includes focused
V9→V10 and V12→V13 upgrade fixtures. Those tests do not replace a rehearsal against
an encrypted copy of the actual deployment database. Follow [the deployment
runbook](DEPLOYMENT.md) for backup, staging validation and recovery planning.

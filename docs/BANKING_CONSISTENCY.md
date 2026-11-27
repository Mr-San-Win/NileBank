# Banking consistency and retry rules

All funds are simulated. PostgreSQL transactions, fixed-precision decimals and
account row locks protect the persisted balances; this is not a double-entry ledger.

## Cash operations

Deposits and withdrawals accept an optional `Idempotency-Key` header (8–100
characters). The built-in browser always supplies one and retains it while retrying
the same open form. The namespace is `(account_id, operation, client_key)`; deposit
and withdrawal keys are independent of transfer, partner and savings retry keys.
A matching successful request returns the original persisted transaction; a changed
amount returns 409. Ownership is checked before replay. Completed operations can
still replay after an account is frozen. New operations require an active account.

V15 adds immutable `cash_requests` records. The primary account row is locked before
looking up a key, so concurrent duplicates serialize. The balance update, audit row
and retry record use the same Spring transaction and datasource. Failed operations
consume no key. The first response reloads the audit row to use PostgreSQL timestamp
precision, matching subsequent replays exactly.

Legacy callers that omit a key retain the existing behavior: every successful call
is a new operation. A new form or browser reload creates a new key. After an uncertain
response, keep the form open and retry unchanged, or inspect history before starting
a new operation. This mechanism does not promise global exactly-once delivery.

## Other money movements

Internal transfers lock their two primary accounts in deterministic UUID order.
Savings, FX and fictional partner movements lock the owning primary account before
child balances. Each successful operation updates both sides and its immutable
history in one transaction. The parent lock also serializes operations across
modules that compete for the same main balance. Savings history and planning goal
allocations are distinct; manual finance records never move bank money.

## Session revocation and the public login page

Credential changes increment a database version. Stale sessions are invalidated on
their next request. Protected APIs return 401; exact public paths continue anonymously
so the user can still load the login page, static modules and a fresh CSRF token.
Security configuration and the revocation filter share the same exact allowlist.
No protected financial endpoint is added to it.

## Verification

Cash tests cover exact replay, payload conflicts, concurrent duplicates, failed
request retries, frozen-account replay, ownership, legacy callers and rollback of
balance/audit/retry data. Account security tests cover public shell access after
revocation alongside protected API rejection. Full-suite totals are recorded in the
implementation status after each completed run.

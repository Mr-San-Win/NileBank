# Simulated savings accounts

Savings accounts hold actual persisted simulated THB balances. They are separate
from finance planning goals/allocations. Creating an account or setting a target
starts/reserves no money; only an explicit main/savings movement changes balances.
No interest accrual, real bank connection or real deposit exists.

## Model and limits

V14 adds savings_accounts as children of the existing primary THB account. Each has
a name, zero initial balance, ACTIVE/FROZEN status, optional positive target and
creation timestamp. A parent supports up to ten accounts; names are unique without
case distinctions. Targets indicate progress, not a balance cap. Target progress can
exceed 100%. The storage capacity matches the primary account's numeric(19,2) limit.
Foreign wallets remain on the primary account; savings movements use THB only.

savings_movements stores direction, amount, parent/child references, retry key,
immutable banking transaction reference and both resulting balances. Composite FKs
prevent a movement from referencing another parent's savings account. Both movement
and banking history are append-only through database triggers.

## Transactions, locks and retries

Every operation locks the main account first and the owned savings account second.
All savings directions and metadata updates use the same order; financial operations
share the main lock with withdrawals, user transfers, exchange and demo partners.
No operation locks two savings children in reverse order.

A movement validates ownership, then checks the parent-scoped idempotency key. Matching
retries return the original persisted result; changed savings account, direction or
amount conflicts. Completed retries remain valid after freezing. New movements require
both accounts to be active. Failed requests roll back their key and may retry after
funding or another correction.

IN debits main and credits savings. OUT debits savings and credits main. Balances,
banking audit and retry record commit atomically. Insufficient funds or destination
capacity failure rolls back the entire operation, including already-written debits.
SAVINGS_IN is an outgoing primary-account audit; SAVINGS_OUT is incoming. Total THB
is conserved by these internal movements. Explicit simulated deposits/withdrawals
remain external sources/sinks in the simulator.

## API

All paths are under /api/v1 and require session authentication. Mutations require CSRF.

- GET /savings/accounts: own accounts and decimal-string balances/target progress.
- POST /savings/accounts: name and optional target; returns a zero-balance account.
- PUT /savings/accounts/{id}: name, optional target and ACTIVE/FROZEN status.
- POST /savings/movements: savingsId, direction IN|OUT, decimal amount; required
  Idempotency-Key header (8-100 letters/digits/hyphens/underscores).
- GET /savings/accounts/{id}/history?page=0&size=20: own immutable movements, max size 100.

The UI reviews movements before confirmation, retains a retry key while details remain
unchanged, and reloads both balances/history after success. Keys live in tab memory;
a reload loses a pending key, so inspect history before beginning a new uncertain action.
No direct savings-to-savings or cross-currency movement is advertised.

## Verification

180 tests pass on PostgreSQL 17.11, including fifteen focused savings tests. They cover
zero/independent planning balances, ownership, conservation, failed-key retry, replay
conflicts, freezing, duplicate concurrency, withdrawals competing with savings,
opposing moves, both destination-overflow rollback paths, immutable SQL history,
concurrent account limits, targets, pagination and authenticated/CSRF-protected APIs.
Existing auth, seven-currency and finance regressions remain. JS syntax and currency
formatting checks pass. Browser walkthrough is part of the final UI review.

This remains an account-balance simulator with an immutable audit, not a production
double-entry ledger or an independently audited financial system.

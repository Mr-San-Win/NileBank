# NileBank JSON API

Base: `/api/v1`. Same-origin session cookie authentication. All money is simulated. Base banking uses THB; exchange wallets additionally support USD/EUR/MMK/SGD/JPY/CNY. Use fictional data.

## CSRF and session lifecycle

1. GET `/auth/csrf` with a cookie-aware HTTP client.
2. Response: `{"headerName":"X-CSRF-TOKEN","token":"<masked token>"}`.
3. Include that header on every state-changing request, including POST register/login/logout and DELETE tracker entries. Preserve the JSESSIONID cookie.
4. After successful login, get a new CSRF token: login rotates the session identifier and invalidates the previous token.
5. Logout is POST only, invalidates the session and clears the cookie.

No bearer token, CORS exemption, or HTTP Basic authentication is used. Do not store passwords or authentication data in browser storage.

## Contracts

Example credentials below are fictional test values, not a preloaded account or real password.

### POST /auth/register → 201

```json
{"fullName":"Demo Student","email":"demo@example.test","password":"a-demo-passphrase"}
```

Name: nonblank, maximum 100 characters. Email: valid, maximum 254 characters; normalized with strip/lowercase. Password: 12–72 characters and maximum 72 UTF-8 bytes. Duplicate email → 409. Does not automatically log in. Returns user DTO: id, fullName, email, createdAt.

### POST /auth/login → 200

```json
{"email":"demo@example.test","password":"a-demo-passphrase"}
```

Sets authenticated session and returns user DTO. Wrong email/password → 401 with generic message. GET `/auth/me` returns the same profile contract.

### POST /accounts → 201

No body. Creates one account for the current user; a second account → 409.

### GET /accounts/me → 200

```json
{"id":"<uuid>","accountNumber":"NB<20 uppercase hex characters>","currency":"THB","balance":"0.00","status":"ACTIVE","createdAt":"<UTC instant>"}
```

No account → 404. There is no read endpoint accepting another user's account ID.

### POST /transactions/deposit or /transactions/withdraw → 201

```json
{"accountId":"<own account UUID>","amount":"125.50"}
```

Positive decimal, at most 2 fractional places, at most 1,000,000 THB. BigDecimal values with extra precision are rejected before persistence. Ownership mismatch/nonexistent account → 404; insufficient funds/frozen account/balance capacity exceeded → 409. Deposits add simulated funds only.

### POST /transfers → 200 (new or replayed)

Header: `Idempotency-Key: <8–100 letters/digits/underscores/hyphens>`. A UUID is suitable.

```json
{"sourceAccountId":"<own UUID>","recipientAccountNumber":"NB<20 uppercase hex characters>","amount":"50.25"}
```

Recipient must exist, differ from sender, and be active. Sender must own source; both accounts must be active for new transfers. Matching same-key retries return the original transaction ID/reference without moving funds again. A changed amount or recipient using an already successful key → 409. Keys are scoped to source accounts. Failed transactions roll back their keys. A replay of an already committed result may succeed even if an account has subsequently frozen.

The frontend retains its key while retrying the same reviewed payload in the same tab. After closing/reloading the tab, inspect history before submitting a new transfer.

### Transaction response

```json
{
  "id":"<uuid>","type":"TRANSFER","status":"SUCCESS","amount":"50.25",
  "direction":"OUT","sourceAccount":"NB<sender>","destinationAccount":"NB<recipient>",
  "reference":"NB-<uuid>","createdAt":"<UTC instant>"
}
```

Direction is relative to the requesting owner. Deposits have null source; withdrawals null destination. Only successful operations appear. No password or recipient profile details are returned.

### GET /transactions?page=0&size=10 → 200

Page is zero-based, nonnegative; size 1–100. Sorted by createdAt descending then id descending.

```json
{"content":[],"page":0,"size":10,"totalElements":0,"totalPages":0}
```

### GET /health → 200

```json
{"status":"UP","mode":"SIMULATED_MONEY"}
```

Executes a database query; database failure is an error rather than a false healthy response.

## Errors

```json
{"status":400,"message":"Please check your input","errors":{"amount":"must be greater than or equal to 0.01"},"timestamp":"<UTC instant>"}
```

400 invalid body/parameters/missing header; 401 unauthenticated or invalid credentials; 403 missing/invalid CSRF or denied request; 404 account/resource unavailable; 405 unsupported method; 409 business conflict/duplicate/idempotency mismatch; 415 unsupported media type; 500 unexpected server error. Unexpected API errors log the exception class without response stack traces or internal details.

On 403, fetch a new CSRF token before submitting again. Retry deposit/withdrawal only with the original key and exact amount; otherwise check history before a new operation. Transfers can be safely retried with their original key and exact parameters.



## Current extensions

See [currency exchange, fictional banks and finance tracking](EXTENSIONS.md) for the new schema, API, transaction guarantees, demonstration steps and limitations. The base account remains THB; foreign wallets support USD/EUR/MMK/SGD/JPY/CNY using real daily reference rates. Tracker entries and savings allocations are planning records separate from bank balances.


### Exchange quote precision

`POST /api/v1/exchange/quotes` retains the existing JSON contract. Monetary response values remain decimal strings. `amount` and `receivedAmount` are the binding persisted terms. `rate` remains an eight-decimal string and is an approximate display rate; clients must not reconstruct settlement from it. `notice` explains the distinction. `POST /exchange/quotes/{id}/execute` uses the stored amount and returns the original result on matching replay, without repricing from a provider refresh. The received amount rounds down once at the destination minor unit; JPY uses zero decimals; THB, USD, EUR, MMK, SGD and CNY use two decimals. No exchange fee is applied.

## Optional Google authentication

`GET /api/v1/auth/options` is public and returns `{ "googleEnabled": false }` by
default. When enabled, navigate the browser to `/oauth2/authorization/google`.
Spring handles `/login/oauth2/code/google`; neither is a JSON claims endpoint.
Success redirects to `/`; failure redirects to `/?authError=google` with no provider
details or tokens. Refresh `/api/v1/auth/csrf` and `/api/v1/auth/me` after login.
Existing password endpoints and POST logout retain their contracts and CSRF checks.
See [authentication design/setup](AUTHENTICATION.md).

## Account security

Authenticated endpoints (POSTs require CSRF):

- `GET /auth/security`: method availability; no provider subject or credentials.
- `POST /auth/security/google/link`: `{ "currentPassword": "..." }`; returns a fixed
  local OAuth redirect after fresh password proof. Intent expires after five minutes.
- `POST /auth/security/google/unlink`: same proof body; returns 204 and signs out.
- `POST /auth/security/password`: `{ "currentPassword": "...", "newPassword": "..." }`;
  returns 204 and signs out. Other sessions are revoked on their next request.

The last sign-in method cannot be removed. Google must be enabled to initiate linking.
Authentication abuse returns 429 with Retry-After. Recovery/MFA endpoints do not exist.
See [account security and limitations](ACCOUNT_SECURITY.md).

Seven-currency coverage, precision, source policy and verification: [currency guide](CURRENCIES.md).

## Personal finance expansion

All paths below are under `/api/v1`; all mutations require session authentication and CSRF.

- `PUT /finance/entries/{id}`: same fields as entry creation; owner-only correction.
- `GET /finance/entries`: adds optional direction/category and sort=DATE_DESC|DATE_ASC|AMOUNT_DESC|AMOUNT_ASC.
- `GET /finance/categories`: active income/expense codes plus labels for existing history.
- `GET/POST /finance/category-management`: list all categories or create `{direction,label}`.
- `PUT /finance/category-management/{code}`: `{label}`; direction/code remain stable.
- `DELETE /finance/category-management/{code}`: archive, preserve history, pause active rules.
- `GET/POST /finance/recurring`: list/create schedules with direction, category, amount,
  description, frequency=DAILY|WEEKLY|MONTHLY, startDate and optional endDate.
- `PUT /finance/recurring/{id}`: future direction/category/amount/description/endDate changes.
- `POST /finance/recurring/{id}/state`: `{action:PAUSE|RESUME|CANCEL}`.
- `POST /finance/recurring/materialize`: bounded idempotent generation; returns `{created:N}`.

Amounts are decimal strings. A generated entry adds recurringRuleId/scheduledFor metadata.
No route moves bank funds. See [recurrence and category policies](PERSONAL_FINANCE.md).

## Simulated savings

See [savings API and integrity policy](SAVINGS.md). `/savings/accounts` lists/creates
zero-balance accounts. `PUT /savings/accounts/{id}` updates name/target/status.
`POST /savings/movements` requires Idempotency-Key and `{savingsId,direction:IN|OUT,amount}`.
Matching retries return original transaction/balances; changed payload conflicts.
`GET /savings/accounts/{id}/history` supports page/size. All amounts are THB decimal
strings. No finance planning allocation automatically funds these accounts.

## Cash operation retries

Deposit and withdrawal accept optional `Idempotency-Key` (8–100 characters).
The browser supplies it. Same account/operation/key and amount replays the original
transaction; changed amount returns 409. Missing keys preserve legacy non-idempotent
behavior. See [banking consistency](BANKING_CONSISTENCY.md).

## Optional password recovery

`GET /auth/options` now also reports `recoveryEnabled`.
`POST /auth/recovery/request` accepts `{ "email": "..." }`, requires CSRF and
returns a generic 202 without tokens. `POST /auth/recovery/reset` accepts
`{ "token": "...", "password": "..." }`, requires CSRF and returns 204 after
consuming a valid delivered link and revoking sessions, or controlled 400 on an
invalid/expired/replayed link. It does not log in. See [recovery details](PASSWORD_RECOVERY.md).

## Optional authenticator MFA

For enrolled profiles, password login returns **202** `{ "mfaRequired": true }`
instead of a profile. Non-enrolled login remains 200 with its existing body.
`GET /auth/mfa/status` reports only session-local `{ "required": true/false }`.
`POST /auth/mfa/challenge` takes `{ "code": "..." }` with CSRF; successful completion
returns the normal profile and rotates session/CSRF state. Pending identities cannot
access banking/profile APIs. Refresh CSRF after completion.

Authenticated full-user management endpoints are documented in [MFA](MFA.md):
`GET /auth/security/mfa` and POST `/auth/security/mfa/enroll`, `/confirm`, `/disable`,
`/recovery-codes`. Management takes `currentPassword` and, when needed, `code`. Setup
seeds and new recovery codes are one-time sensitive responses; never log or persist
them in browser storage. Factor changes sign out sessions.

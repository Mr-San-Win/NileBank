# NileBank JSON API

Base: `/api/v1`. Same-origin session cookie authentication. All money is simulated. Base banking uses THB; exchange wallets additionally support USD/EUR. Use fictional data.

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

400 invalid body/parameters/missing header; 401 unauthenticated or invalid credentials; 403 missing/invalid CSRF or denied request; 404 account/resource unavailable; 405 unsupported method; 409 business conflict/duplicate/idempotency mismatch; 415 unsupported media type; 500 unexpected server error. Stack traces are logged server-side and never included in response JSON.

On 403, fetch a new CSRF token before submitting again. Do not automatically retry deposit/withdrawal after a lost response; check history. Transfers can be safely retried with their original key and exact parameters.



## Current extensions

See [currency exchange, fictional banks and finance tracking](EXTENSIONS.md) for the new schema, API, transaction guarantees, demonstration steps and limitations. The base account remains THB; foreign wallets support USD/EUR using real daily reference rates. Tracker entries and savings allocations are planning records separate from bank balances.

# NileBank internship interview guide

Describe it honestly: “I built a Java/Spring Boot banking simulator with PostgreSQL, secure sessions, atomic transfers, real reference-rate simulated currency exchange, fictional partner banks and a manual finance tracker. It is educational software, not a real bank.” Be prepared to trace the code and explain a test, rather than claiming production banking experience.

## 1. A layered, same-origin monolith

**Decision:** One Spring Boot app serves static ES modules and REST APIs. Controllers validate DTOs, services enforce ownership/business rules, and repositories persist records. PostgreSQL provides constraints and row locks; Flyway versions the schema.

**How to explain it:** “A monolith was enough for the scope. It let me keep one deployment and one database transaction, without microservice coordination. Services keep business rules independent from the browser and HTTP controller.”

**Demonstrate:** Follow a withdrawal from its controller to its service, repository and history insert. Explain why hiding a button cannot enforce ownership. Tradeoff: this design does not independently scale each feature, but is simpler to understand and operate.

## 2. Server-side sessions, BCrypt and CSRF

**Decision:** Spring Security authenticates with adaptive BCrypt hashes and server-side sessions. HTTP-only, SameSite cookies carry the session ID. The frontend fetches a CSRF token and sends it on mutations. Login rotates session identity and CSRF state; logout invalidates the session.

**How to explain it:** “For a same-origin app, sessions avoid managing browser bearer tokens. A password hash is not encryption: we verify a supplied password against a deliberately expensive hash. CSRF protection matters because browsers attach cookies automatically.”

**Demonstrate:** Show the unauthorized-access, invalid-login and missing-CSRF tests. Know the distinction between authentication (who you are), authorization (what you own/can do), and CSRF (whether a cookie-authenticated mutation came through the expected client flow). HTTPS and secure cookies are required beyond localhost.

## 3. Exact money and explicit concurrency control

**Decision:** BigDecimal and PostgreSQL numeric(19,2) represent amounts, with validation before storage. Monetary services lock account rows before reading/checking balances. Multi-account transfers acquire locks in deterministic UUID order.

**How to explain it:** “Two concurrent withdrawals can both see sufficient funds unless their read/check/write steps serialize. @Transactional provides a transaction boundary; the row lock prevents both requests from approving against the same old balance. Ordered locks reduce deadlocks.”

**Demonstrate:** Run the simultaneous-withdrawal test on real PostgreSQL and explain why H2 would not prove PostgreSQL locking behavior. Explain why binary floating-point is unsuitable for decimal money. API money is returned as strings to preserve precision in JavaScript. Tradeoff: contention makes writers wait, so this is correctness-focused rather than a throughput claim.

## 4. Atomicity and retry-safe idempotency

**Decision:** Debit, credit, immutable history and the idempotency record commit together. Keys are scoped to an account/source, protected by uniqueness, and compared with the original payload. Matching repeats return the original result; changed payloads conflict. Failed work rolls back, including the key.

**How to explain it:** “Atomicity prevents half a transfer. Idempotency handles a different problem: the transfer succeeded but the client lost the response and retries. A transaction alone does not prevent a second valid request from moving money again.”

**Demonstrate:** Show the recipient-capacity failure test that rolls back a sender debit, then the duplicate-key test that moves funds only once. Exchange uses a saved quote UUID; partner movements use a client key and saved result. Know the limitation: ordinary deposits/withdrawals are not idempotent, so ambiguous results need history inspection.

## 5. Honest boundaries around external data and analysis

**Decision:** Fetch real daily ECB reference rates through Frankfurter, expose publication/retrieval times, persist short-lived quotes, and fail new quotes when data is unavailable. Fetch before acquiring monetary locks. Partner banks remain fictional. Personal finance inputs and savings allocations are separate planning records.

**How to explain it:** “External data can be stale or unavailable. Refresh means fetching the latest publication, not promising a new market price. A saved quote defines what the user confirmed. I keep network delays outside monetary locks and distinguish cash movement from income or spending.”

**Demonstrate:** Refresh the rate screen, review a quote, confirm once, then inspect saved history. Explain the provider-validation and replay tests. Show a category budget insight and its arithmetic. Do not describe the rule-based insights as an AI predictor or savings allocations as reserved bank funds.

## Before the interview

Run `mvn verify` with Docker available and read the actual test output. Walk through registration, two-user transfer, exchange, fictional bank movement and an over-budget tracker entry. Explain one failure path and one concurrency test in your own words.

A real financial system would need an independently designed double-entry ledger, reconciliation, identity/regulatory controls, abuse/fraud prevention, recovery/MFA, observability, disaster recovery and independent security review. NileBank has no real payment integration, public production deployment or proven performance benchmark. Being clear about these limits is part of good engineering.

# Talking about VaultBank

## Why Spring Boot and PostgreSQL?
Spring Boot integrates HTTP, validation, security, persistence and testing with conventional configuration. PostgreSQL supplies fixed-precision decimals, constraints, transactions and row locks. A single application keeps the project understandable.

## Layers
Controllers translate HTTP requests into validated DTOs. Services enforce ownership and banking rules and define transaction boundaries. Repositories load and persist entities. Keeping rules in services protects operations even when called outside an HTTP controller.

## Authentication
A password is stored as an adaptive BCrypt hash, never plaintext. A successful login creates a server-side authenticated session. The browser receives an HTTP-only session cookie. State-changing requests also need a CSRF token, so an unrelated website cannot use the session cookie to submit banking commands.

## Why BigDecimal?
Binary floating-point cannot represent many decimal fractions exactly. BigDecimal with a two-decimal validation rule and PostgreSQL numeric(19,2) preserves THB amounts. Limits must be checked before persistence so the database cannot silently round invalid inputs.

## Transactions and atomicity
`@Transactional` groups related database work: either all changes commit or all roll back. A transfer must debit the sender, credit the recipient and record history together. A transaction alone does not stop two requests from reading the same old balance.

## Race conditions
A PostgreSQL write lock makes another writer wait before reading and changing the account. Transfers lock accounts in the same deterministic order to reduce deadlocks. Integration tests must exercise real PostgreSQL because an in-memory substitute can have different locking semantics.

## Idempotency
A client retries a transfer with the same unique key after a lost response. The server returns the stored original result without moving money again. Reusing a key with different parameters is an error. The key and balance changes must commit together.

## What a real bank needs
A production financial system needs a carefully audited double-entry ledger, reconciliation, legal and regulatory controls, stronger identity verification, fraud detection, abuse controls, operational monitoring, disaster recovery, independent security review and much more. This portfolio simulator demonstrates selected engineering ideas; it does not implement those guarantees.

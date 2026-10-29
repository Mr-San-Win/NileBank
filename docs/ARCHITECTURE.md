# Architecture

VaultBank is a same-origin Spring Boot monolith serving JSON APIs and static browser assets. All funds are simulated THB. PostgreSQL is the source of truth; Flyway owns migrations and Hibernate validates mappings.

## Request flow
Browser → Spring Security (session and CSRF) → validated controller DTO → transactional service → JPA repository → PostgreSQL. Responses contain DTOs, never password hashes or persistence entities.

## Planned consistency model
Account balances use numeric(19,2) / BigDecimal. Monetary services acquire PostgreSQL pessimistic write locks before reading balances. Transfers lock both account rows in deterministic UUID order, validate ownership and eligibility, then debit, credit, and insert an audit record within one transaction. A failure rolls all changes back.

Transfer keys are scoped to the sender. The locked source account serializes competing requests; the committed transaction stores the original key and transfer parameters under a database uniqueness constraint. Same-key retries return the original record; changed parameters conflict. Failed requests roll back the key and may be retried.

## Tradeoffs
One account per user, one currency, bounded amounts, synchronous requests, no external payment integrations. Audit history records successful operations only. This is not a production double-entry ledger; no financial compliance, settlement, reconciliation, or fraud controls are provided.

This document will be updated to match verified implementation.

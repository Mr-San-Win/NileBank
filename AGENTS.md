# NileBank engineering rules
Java 21 / Spring Boot layered monolith. Static HTML/CSS/ES modules use same-origin JSON APIs. Simulated money only. THB base accounts with USD/EUR demo exchange wallets; fictional partner banks and a separate THB finance tracker.

Controllers validate HTTP input; services own authorization and transaction boundaries; repositories persist entities. Return DTOs, never password hashes. Use BigDecimal and PostgreSQL numeric(19,2). All monetary mutations require pessimistic account row locks; lock multiple accounts in UUID order. Transfer idempotency must commit with balance changes and history. Do not weaken CSRF or replace PostgreSQL concurrency tests with H2.

Commands: `mvn test`, `mvn verify`, `docker compose up --build`. Copy .env.example to ignored .env and choose a local password. Testcontainers requires Docker.

Branch: codex/nilebank-mvp. The owner authorized final verification, commits and publication to this branch on 2026-10-30. Review diffs, run meaningful tests, stage intended files, commit milestones, push only this branch. Never force-push, overwrite unrelated work, commit secrets, or merge main. Record actual results and blockers in docs/BUILD_LOG.md.


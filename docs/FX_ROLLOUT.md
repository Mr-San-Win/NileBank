# FX settlement rollout

This procedure requires a full application maintenance window. Apply the same drain
when changing FX_REFERENCE_PROVIDER; do not mix provider configurations across live instances. There is no built-in
quote-only maintenance switch or settlement-version field. An old unexpired quote
can still execute its persisted old settlement amount. Do not deploy the correction
with such quotes executable, and do not reprice or delete existing quote rows.

## Offline drain

1. Inventory every application instance and other process able to create or execute
   quotes. Block public ingress using the deployment's existing maintenance controls,
   if available. The repository does not configure an ingress controller.
2. From the repository root, stop **all** old application instances. For the supplied
   single-instance Compose topology, use `docker compose stop --timeout 60 app`.
   Keep `db` running. Confirm `docker compose ps --all app` reports the app stopped;
   confirm any other JVMs/replicas are stopped too. Shutdown lets requests finish;
   forced termination closes connections and PostgreSQL rolls back uncommitted work.
   Do not start the expiry wait until every old process has terminated. If instance
   inventory or shutdown cannot be confirmed, stop the rollout.
3. Query the running database, using container environment variables rather than
   displaying credentials:

   ```sh
   docker compose exec -T db sh -c 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT CURRENT_TIMESTAMP AS database_time,
       count(*) AS outstanding_quotes,
       max(expires_at) AS last_expiry
FROM exchange_quotes
WHERE executed_transaction_id IS NULL
  AND expires_at > CURRENT_TIMESTAMP;
SQL
   ```

   The column names and relationships are defined in the existing V7 migrations.
   The filter selects unexecuted quotes that are still unexpired by database time.
4. Keep all application instances stopped. Wait until after `last_expiry`, with a
   conservative clock margin. Quotes normally expire 120 seconds after creation,
   but use the persisted maximum rather than assuming that duration. Confirm host,
   database and new JVM clocks are synchronized: execution uses JVM `Instant.now()`,
   not database time. A zero count from a fast database clock alone is insufficient.
   If clock alignment is uncertain, keep the service offline until resolved.
5. Re-run the query in a fresh invocation and require `outstanding_quotes = 0`.
   With no writers and synchronized clocks, no old unexecuted quote is executable.
   Do not alter expiration timestamps, settlement amounts, executed references or
   transaction history to manufacture this result.
6. Build and start the corrected application only after the drain check:
   `docker compose build app`, then `docker compose up -d --no-deps app`.
   Check startup/health and a fresh quote's amounts before restoring ingress.
   If there is no upstream maintenance gate, starting the app restores local access;
   complete the drain and clock checks before starting it.
7. Preserve previously executed quotes and their transaction references. Execution
   returns their persisted result before checking expiry, so retries must keep the
   same transaction ID and amounts. An old **unexecuted** expired quote must return
   HTTP 409 without moving money; a new quote must use corrected settlement.

## Operational limits and verification

No commands above stop or modify the database, erase data or modify migrations.
This is an operator procedure, not an automatic deployment guard. There is no
zero-downtime quote-only drain mechanism today. If a maintenance window is not
acceptable, the smallest additional mechanism would be an enforced quote-creation
pause at the ingress (with all instances drained), or a shared application pause
control. Either requires separate review; neither is implemented here.

Compose configuration and stop/exec CLI options were checked during hardening.
The SQL is exercised against PostgreSQL 17 by the automated drain regression test.
The local Compose database was stopped, so a direct operational query could not run. No shutdown,
deployment or production drain was performed. Automated tests separately verify
expiry, ownership, persisted execution, replay, rollback and concurrent operations.

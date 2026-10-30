# Five-minute interview demo

Use the Docker startup instructions in README. Every name, account, balance and payment here is fictional.

1. Show the demo notice. Register Alex Example with an example.test email and a dedicated demo password. Sign in.
2. Create a virtual account. Explain that its initial balance is 0.00 THB.
3. Deposit 1,000.50 simulated THB. Withdraw 100.25. Show the 900.25 balance and two persisted history rows.
4. Copy Alex's generated account number. Sign out. Register and create Jamie Example's account, then copy that account number.
5. Sign out and sign back in as Alex. Transfer 250.25 to Jamie. Show the review step, confirm it, and inspect the 650.00 sender balance and reference.
6. Sign in as Jamie and verify the 250.25 received balance and matching reference. A separate browser profile can retain both sessions; ordinary tabs share a session cookie.
7. Attempt to withdraw more than the balance. Show the error and unchanged history/balance.
8. Discuss the tests: multiple threads start together against PostgreSQL, pessimistic locks prevent overspending, transaction rollback prevents partial transfers, and a persisted idempotency key prevents duplicate retries.

Show the code in TransferService and the PostgreSQL concurrency tests. Explain why transaction boundaries and locks solve different problems. Do not call this real banking or production-ready software.

## Troubleshooting

- Docker unavailable: start Docker Desktop's Linux engine. Tests need real Docker/PostgreSQL.
- Missing DB_PASSWORD: copy/edit the ignored .env file.
- App unhealthy: `docker compose logs app db`, then check `docker compose ps`. Do not post environment files or passwords.
- Port busy: stop the conflicting local process or change the loopback port mapping deliberately.
- Initial build fails downloading dependencies: check connectivity to Maven Central/Docker Hub and retry after the condition changes. No offline build is promised.
- Changed database password with an existing volume: changing POSTGRES_PASSWORD does not change an already initialized database role. Restore the original local password or deliberately migrate the database credential. Do not delete the volume to hide the issue.
- Session expires or app restarts: sign in again; persisted balances remain in PostgreSQL.
- Lost monetary response: check history before repeating. Transfer retries must reuse the original idempotency key.



## Current extensions

See [currency exchange, fictional banks and finance tracking](EXTENSIONS.md) for the new schema, API, transaction guarantees, demonstration steps and limitations. The base account remains THB; foreign wallets support USD/EUR using real daily reference rates. Tracker entries and savings allocations are planning records separate from bank balances.

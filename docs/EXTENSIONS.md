# NileBank extensions

All balances and partner movements remain simulated. Currency rates are real public reference data; they do not make the simulator a real banking service.

## Try the new screens

Start `docker compose up --build` in `/home/san_win/NileBank`, then open http://localhost:8080. Register a fictional profile, create an account and deposit simulated THB.

1. **Currency exchange:** refresh rates and inspect the source, publication date and retrieval time. Request a THB/USD/EUR quote, review the amount received, then confirm. Wallet balances and transaction history update. Replaying the same quote returns the original exchange.
2. **Demo banks:** consent to connect Harbor Demo Bank or Orchid Demo Bank. Each fictional connection starts with 5,000 simulated THB once. Review and confirm a movement in either direction. Disconnecting and reconnecting preserves its balance.
3. **Finance tracker:** enter manual income and expenses, choose a month, set category budgets, and inspect the recorded surplus and budget insights. Create a savings goal and allocate a planning amount. These records do not debit or reserve bank funds.

## Real internet rates

Every rates refresh and new quote requests Frankfurter v2 with ECB data over HTTPS: https://api.frankfurter.dev/v2/rates?base=EUR&quotes=THB,USD&providers=ecb . Documentation: https://frankfurter.dev/ . No user data is sent to this provider.

These are daily reference rates, not executable market prices. A refresh can return the same published rate, especially on weekends or holidays. The interface exposes publication date and retrieval time. Unsupported, malformed, more-than-seven-day-old or unavailable data rejects new quotes with a service-unavailable response. There is no fixed-rate fallback. Existing unexpired quotes retain their agreed rate. Quotes last 120 seconds, and received amounts round down to two decimals. No simulated exchange fee is charged.

## Persistence and consistency

Flyway V7 adds foreign wallets and exchange quotes, V8 adds fictional partner connections and idempotent movements, and V9 adds finance entries, budgets and savings goals. Original migrations are unchanged.

Exchange and partner operations lock the same parent account row used by ordinary withdrawals/transfers. JDBC writes join the Spring-managed database transaction. Balance changes, quote execution or partner idempotency state, and immutable banking history commit together. Exchange replay is keyed by quote UUID; partner replay uses a client Idempotency-Key and rejects changed parameters. A failed operation rolls back all financial writes. Goals use row locks to prevent concurrent allocations exceeding their target.

Manual finance records are deliberately separate from banking history: a deposit or transfer is not automatically income or spending. Insights use explainable arithmetic on recorded entries and category budgets, not a prediction model or personalized financial advice. Missing entries make the analysis incomplete; current and previous months may contain different numbers of days.

## API additions

All routes start with `/api/v1`, require authentication, and use the existing CSRF token for mutations. Decimal monetary fields are JSON strings in responses.

| Method | Path | Body / purpose |
| --- | --- | --- |
| GET | /exchange/rates | Fetch current provider publication |
| GET | /exchange/wallets | Own THB/USD/EUR balances |
| POST | /exchange/quotes | fromCurrency, toCurrency, amount |
| POST | /exchange/quotes/{id}/execute | Execute or replay own quote |
| GET | /partners/banks | Fictional allowlist |
| GET | /partners/connections | Own connections |
| POST | /partners/connections | bankCode, demoConsent=true |
| POST | /partners/connections/{id}/disconnect | Preserve balance, disconnect |
| POST | /partners/transfers | connectionId, direction=IN/OUT, amount; Idempotency-Key header |
| GET | /finance/categories | Income/expense categories |
| GET | /finance/entries?month=YYYY-MM&page=0&size=10 | Own monthly paginated entries |
| POST | /finance/entries | direction=INCOME/EXPENSE, category, amount, description, occurredOn |
| DELETE | /finance/entries/{id} | Delete own manual entry |
| POST | /finance/budgets | month, category, amount |
| GET | /finance/summary?month=YYYY-MM | Recorded totals, category budgets, insights |
| GET | /finance/goals | Own goals |
| POST | /finance/goals | name, target |
| POST | /finance/goals/{id}/allocations | amount; planning allocation |

## Verification and limits

The integrated Maven suite passed 56 tests against PostgreSQL with no failures, errors or skips. It covers exchange replay/expiry/ownership/rollback, shared-lock contention, partner replay/conflict/rollback/reconnection, and finance isolation/arithmetic/pagination/concurrent goal allocation, plus the original banking/security tests. Provider parser tests use deterministic fixtures; the separate HTTP smoke script exercises the actual public provider.

Run `mvn verify`, then `python3 scripts/smoke_extensions.py` against the running Docker application. The latter requires internet access and creates fictional persisted demo records. Both smoke scripts use only the Python standard library.

No real bank connectors, payments, bank credential storage, automatic transaction categorization, recurring entries, live trading feed or goal withdrawals are implemented. Foreign balances can be exchanged back to THB; ordinary withdrawals and account-to-account transfers remain THB only. See BUILD_LOG.md for the authorized final commit and publication status.

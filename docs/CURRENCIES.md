# Currencies and reference-rate sources

## Supported wallets

THB remains the primary account. USD, EUR, MMK, SGD, JPY and CNY are foreign wallets.
Currency metadata comes from Java's ISO currency data: JPY has zero fractional digits;
the other six currently have two. Inputs enforce the source precision; settlement
rounds down once at the destination precision. Storage remains numeric(19,2), with
additional database whole-unit checks for JPY wallet balances, quote amounts and audit
amounts. No balance is represented using binary floating point.

V12 extends currency constraints without altering existing applied migrations or
existing wallet balances. Wallets begin at zero and are funded only by simulated
exchange. Deposits, withdrawals, user transfers and fictional partner movements
continue to use the primary THB account.

## Pinned official sources, never invented fallback

Frankfurter v2 provider metadata was inspected on 21 November 2026:

- [CBM metadata](https://api.frankfurter.dev/v2/providers/cbm) identifies Central Bank
  of Myanmar reference data and includes all seven requested currencies.
- [MMK metadata](https://api.frankfurter.dev/v2/currency/mmk) lists CBM coverage.
- [ECB metadata](https://api.frankfurter.dev/v2/providers/ecb) covers the six other
  currencies but does not list MMK.
- [Frankfurter reference documentation](https://frankfurter.dev/) distinguishes
  reference rates from live market prices and supports pinning a provider.

`FX_REFERENCE_PROVIDER=cbm` is the default. The application requests one EUR-base
snapshot for all six targets, pinned to CBM. The actual application provider fetched
all seven currencies successfully with publication date 2026-11-19 during verification.
This verifies reference-data retrieval, not execution at real market prices. Official
reference values should not be presented as a guaranteed retail/cash exchange rate.

`FX_REFERENCE_PROVIDER=ecb` requests a six-currency ECB snapshot. MMK wallets remain
visible, but MMK quotes return controlled unavailable responses and are disabled in
the selector. No cross-provider rate blending or fabricated fallback occurs. A new
source can implement the existing ExchangeRateProvider boundary without changing
settlement. Unsupported provider configuration fails explicitly.

Every quote/refresh fetches a current published snapshot. Rates may remain unchanged
on weekends or delayed publication days. Missing, invalid, inconsistent-date, future
or more-than-seven-day-old data fails closed. All declared currency weights must be
present and strictly positive. Snapshot maps and declared currencies are immutable.
Legacy three-currency fixture constructors retain their original validation contract.

## Exact settlement and audit

The original published decimal numerator/denominator determines settlement. The
eight-decimal display rate is approximate; persisted source and received amounts
remain authoritative. Execution never refetches or reconstructs them from a display
rate. Quotes expire after 120 seconds; execution/replay, ownership, parent-account
locking and rollback remain shared with the existing exchange implementation.
Audit descriptions now use the persisted provider name rather than a fixed ECB label.

A fixed reference snapshot cannot gain value from splitting, round trips or cycles
under this downward-rounding rule. Different dated/provider snapshots are different
prices; those are not a guarantee of cross-publication price conservation. Before
changing provider configuration, stop all old instances and drain outstanding quotes
using [the offline rollout procedure](FX_ROLLOUT.md). Do not reprice old quotes.

## API and frontend

- `GET /api/v1/exchange/currencies`: supported codes and fractional digits.
- Wallets show all seven currencies, including integer JPY values.
- Rates show only the selected provider's available currencies and provenance.
- Quotes accept all supported pairs covered by that snapshot; API shapes are retained.
- Source limits remain 1,000,000 units and 1,000,000 THB equivalent.
- Frontend controls use backend currency metadata. Monetary formatting works on decimal
  strings; approximate reference weights retain their displayed precision.

## Verification

143 tests pass, including all original FX-001 regressions plus all 42 directed pairs,
round trips, split conversions, complete seven-currency cycles, JPY input/database
precision, tiny values, coverage failures, concurrent replay, overflow rollback,
source attribution and immutable declarations. Core tests use fixed fixtures and
never depend on provider availability. A separate real provider fetch succeeded.

Browser CLDR currency defaults can differ from backend ISO metadata (MMK exposed
this during verification). The formatter now uses the backend-provided scale and
operates on decimal strings. `node scripts/check_currency_format.mjs` passes seven
assertions covering large balances, MMK fractions, JPY whole units and invalid precision.

# Personal finance records and recurring schedules

Finance records are owner-scoped THB planning data. Manual income/expenses, budgets,
recurring entries and planning-goal allocations never debit or credit bank wallets.
Bank transfers, partner movements and currency exchanges are not automatically income.

## Entries, categories and budgets

Entries support create, edit, delete, month/type/category filters and deterministic
DATE_ASC, DATE_DESC, AMOUNT_ASC or AMOUNT_DESC sorting. SQL uses bound parameters;
ordering comes only from a server allowlist. Updates/deletes include owner predicates.
Generated entries retain their original rule/date tag even when manually corrected.

V13 seeds eleven independent default categories per user, backfills existing users,
and seeds new users in the profile-insert transaction. Each category has an immutable
code/direction, editable label and archive flag. Active labels are unique per owner
and direction. Composite foreign keys enforce category ownership and income/expense
compatibility for entries, budgets and rules. Renaming does not reclassify history.
Archiving preserves old records and budgets, prevents new assignment and pauses active
rules using the category. Historical entries may still be corrected in their existing
archived category. A rule must choose an active category before resuming.

Monthly reports calculate income, expenses, net cash flow, category spending, budget
remaining/percentage, over-budget indicators and previous-month expenses. Explanations
are deterministic and based only on recorded data; missing income yields no invented
savings percentage. This is not personalized financial advice or a prediction.

## Recurrence policy

- Dates use Asia/Bangkok, not a browser's timezone or a server-local midnight.
- DAILY and WEEKLY advance one/seven calendar days from their scheduled dates.
- MONTHLY preserves the original start day: Jan 31 -> Feb 28 -> Mar 31 (Feb 29 in
  leap years). No cumulative short-month drift occurs.
- Active rules catch up missed dates, inclusive of their optional end date. Each run
  processes at most ten due rules and one hundred occurrences per rule, oldest first.
  Large backlogs progress over subsequent runs rather than locking the service indefinitely.
- Pausing prevents generation. Resuming chooses the first scheduled date on/after
  today and skips paused past dates. Resuming an already-active rule preserves backlog.
- Amount/category/description/end-date edits affect only future generated entries.
  Start date and frequency are immutable; cancel/recreate to change the anchor.
- Cancelled schedules cannot resume. Ended schedules become COMPLETED. Extend an end
  date before resuming a completed schedule. Existing entries are never rewritten.

## Transactions, retries and concurrency

The generator locks each rule with FOR UPDATE SKIP LOCKED. A unique (rule_id,date)
occurrence claim, tracker entry and cursor advancement commit in one transaction.
Other workers skip a locked rule. Failure rolls back the claim, entry and cursor,
allowing safe retry. A batch is atomic per rule, not across all users' rules.

Occurrence tombstones persist if a generated entry is deleted. A cursor replay cannot
resurrect that deleted occurrence. Entries additionally have unique rule/date tags and
owner/rule foreign keys. Scheduled generation never touches the financial ledger.

Category archival and rule updates follow compatible category/rule locking rules;
FK checks can complete while archival waits for a generator. An integration race test
verifies no deadlock and no generation after archival completes.

The background scheduler runs once per minute by default. Set
FINANCE_SCHEDULER_ENABLED=false to pause background processing only; the authenticated,
CSRF-protected materialization action remains available. The UI refreshes due records
before loading reports. This is a database-backed schedule, not a message broker.

## Tests and operational boundaries

Full Maven verification passed 165 tests on PostgreSQL 17.11. It includes a populated
V12-to-V13 upgrade, schema-safe default seeding, owner/direction FK checks, custom
budgets, entry editing/filtering, calendar cases, concurrent workers, bounded catch-up,
rollback, tombstones, category archival races and all prior banking/auth/FX regressions.
The background timer is disabled in test contexts to avoid nondeterministic wall-clock
work; the actual generator is exercised with a fixed clock and real PostgreSQL.

JS syntax, literal DOM references and seven exact currency-format assertions passed.
A new browser walkthrough is still required; backend checks do not establish UI quality.

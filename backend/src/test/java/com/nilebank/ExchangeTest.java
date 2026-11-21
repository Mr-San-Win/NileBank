package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.nilebank.account.*;
import com.nilebank.common.ApiException;
import com.nilebank.exchange.*;
import com.nilebank.transaction.*;
import com.nilebank.user.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ExchangeTest extends PostgresTest {
  @org.springframework.test.context.bean.override.mockito.MockitoBean ExchangeRateProvider provider;

  @org.junit.jupiter.api.BeforeEach
  void ratesFixture() {
    org.mockito.Mockito.when(provider.fetch())
        .thenReturn(
            new ExchangeRateProvider.Snapshot(
                "Test fixture only",
                java.time.LocalDate.now(java.time.ZoneOffset.UTC),
                java.time.Instant.now(),
                Map.of(
                    "THB",
                    BigDecimal.ONE,
                    "USD",
                    new BigDecimal("35.00"),
                    "EUR",
                    new BigDecimal("38.00"))));
  }

  @Test
  void refreshFetchesProviderAgainAndFailureNeverUsesFixedFallback() {
    var first = exchange.rates();
    assertThat(first.mode()).isEqualTo("REAL_REFERENCE_RATES");
    org.mockito.Mockito.when(provider.fetch())
        .thenReturn(
            new ExchangeRateProvider.Snapshot(
                "Updated fixture",
                java.time.LocalDate.now(java.time.ZoneOffset.UTC),
                java.time.Instant.now(),
                Map.of(
                    "THB",
                    BigDecimal.ONE,
                    "USD",
                    new BigDecimal("36.00"),
                    "EUR",
                    new BigDecimal("39.00"))));
    assertThat(exchange.rates().rates().get(1).thbPerUnit()).isEqualTo("36.00");
    org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(2)).fetch();
    org.mockito.Mockito.when(provider.fetch())
        .thenThrow(
            new ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Unavailable"));
    var f = fixture("100");
    assertThatThrownBy(() -> exchange.quote(f.owner(), "THB", "USD", BigDecimal.TEN))
        .isInstanceOf(ApiException.class);
    assertThat(balance(f, "THB")).isEqualTo("100.00");
  }

  @Autowired ExchangeService exchange;
  @Autowired AccountService accounts;
  @Autowired BankingService banking;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;

  record Fixture(UUID owner, UUID account) {}

  Fixture fixture(String balance) {
    var user =
        users.saveAndFlush(
            new BankUser("Exchange Student", UUID.randomUUID() + "@example.test", "not-for-auth"));
    var a = accounts.create(user.getId());
    if (new BigDecimal(balance).signum() > 0)
      banking.deposit(user.getId(), a.id(), new BigDecimal(balance));
    return new Fixture(user.getId(), a.id());
  }

  String balance(Fixture f, String currency) {
    return exchange.wallets(f.owner()).stream()
        .filter(w -> w.currency().equals(currency))
        .findFirst()
        .orElseThrow()
        .balance();
  }

  @Test
  void exchangeDebitsAndCreditsExactlyAndReplaysOnce() {
    var f = fixture("350");
    var q = exchange.quote(f.owner(), "THB", "USD", new BigDecimal("350"));
    assertThat(q.receivedAmount()).isEqualTo("10.00");
    var first = exchange.execute(f.owner(), q.id());
    var repeat = exchange.execute(f.owner(), q.id());
    assertThat(repeat.transactionId()).isEqualTo(first.transactionId());
    assertThat(balance(f, "THB")).isEqualTo("0.00");
    assertThat(balance(f, "USD")).isEqualTo("10.00");
    assertThat(banking.history(f.owner(), 0, 10).content().getFirst().type())
        .isEqualTo(BankingTransaction.Type.EXCHANGE);
    var back = exchange.quote(f.owner(), "USD", "THB", new BigDecimal("10"));
    exchange.execute(f.owner(), back.id());
    assertThat(balance(f, "THB")).isEqualTo("350.00");
  }

  @Test
  void insufficientFundsDoesNotPartiallyCreditAndCanRetryAfterFunding() {
    var f = fixture("0");
    var q = exchange.quote(f.owner(), "THB", "EUR", new BigDecimal("38"));
    assertThatThrownBy(() -> exchange.execute(f.owner(), q.id()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Insufficient");
    assertThat(balance(f, "EUR")).isEqualTo("0.00");
    banking.deposit(f.owner(), f.account(), new BigDecimal("38"));
    exchange.execute(f.owner(), q.id());
    assertThat(balance(f, "EUR")).isEqualTo("1.00");
  }

  @Test
  void expiredQuotesAndCrossUserExecutionAreRejected() {
    var f = fixture("100");
    var stranger = fixture("100");
    var q = exchange.quote(f.owner(), "THB", "USD", BigDecimal.TEN);
    assertThatThrownBy(() -> exchange.execute(stranger.owner(), q.id()))
        .isInstanceOf(ApiException.class)
        .hasMessage("Quote not found");
    jdbc.update(
        "update exchange_quotes set expires_at=current_timestamp-interval '1 second' where id=?",
        q.id());
    assertThatThrownBy(() -> exchange.execute(f.owner(), q.id()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("expired");
    assertThat(balance(f, "THB")).isEqualTo("100.00");
  }

  @Test
  void invalidPairsPrecisionAndEquivalentLimitsAreRejected() {
    var f = fixture("100");
    assertThatThrownBy(() -> exchange.quote(f.owner(), "USD", "USD", BigDecimal.ONE))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> exchange.quote(f.owner(), "BTC", "THB", BigDecimal.ONE))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> exchange.quote(f.owner(), "USD", "THB", new BigDecimal("30000")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> exchange.quote(f.owner(), "THB", "USD", new BigDecimal("0.001")))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void concurrentQuoteRetriesCannotMoveMoneyTwice() throws Exception {
    var f = fixture("350");
    var q = exchange.quote(f.owner(), "THB", "USD", new BigDecimal("350"));
    var ready = new CountDownLatch(8);
    var start = new CountDownLatch(1);
    List<Future<UUID>> results = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(8)) {
      for (int i = 0; i < 8; i++)
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return exchange.execute(f.owner(), q.id()).transactionId();
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      Set<UUID> ids = new HashSet<>();
      for (var result : results) ids.add(result.get(30, TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
    }
    assertThat(balance(f, "THB")).isEqualTo("0.00");
    assertThat(balance(f, "USD")).isEqualTo("10.00");
  }

  @Test
  void exchangeAndWithdrawalShareTheSameAccountLock() throws Exception {
    var f = fixture("100");
    var q = exchange.quote(f.owner(), "THB", "USD", new BigDecimal("80"));
    var start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(2)) {
      results.add(
          pool.submit(
              () -> {
                start.await();
                try {
                  exchange.execute(f.owner(), q.id());
                  return true;
                } catch (ApiException e) {
                  if (!e.getMessage().startsWith("Insufficient")) throw e;
                  return false;
                }
              }));
      results.add(
          pool.submit(
              () -> {
                start.await();
                try {
                  banking.withdraw(f.owner(), f.account(), new BigDecimal("80"));
                  return true;
                } catch (ApiException e) {
                  if (!e.getMessage().startsWith("Insufficient")) throw e;
                  return false;
                }
              }));
      start.countDown();
      int success = 0;
      for (var result : results) if (result.get(30, TimeUnit.SECONDS)) success++;
      assertThat(success).isEqualTo(1);
    }
    assertThat(balance(f, "THB")).isEqualTo("20.00");
  }

  private static final Map<String, BigDecimal> FIXED_WEIGHTS =
      Map.of("THB", BigDecimal.ONE, "USD", new BigDecimal("35"), "EUR", new BigDecimal("38"));
  private static final List<String> CURRENCIES = List.of("THB", "USD", "EUR");

  // Independent inequalities identify the greatest payable minor unit without dividing rates.
  void assertFairQuote(ExchangeService.QuoteView q) {
    BigDecimal sourceValue =
        new BigDecimal(q.amount()).multiply(FIXED_WEIGHTS.get(q.fromCurrency()));
    BigDecimal received = new BigDecimal(q.receivedAmount());
    BigDecimal targetWeight = FIXED_WEIGHTS.get(q.toCurrency());
    BigDecimal quantum =
        BigDecimal.ONE.movePointLeft(
            Currency.getInstance(q.toCurrency()).getDefaultFractionDigits());
    assertThat(received.multiply(targetWeight)).isLessThanOrEqualTo(sourceValue);
    assertThat(received.add(quantum).multiply(targetWeight)).isGreaterThan(sourceValue);
  }

  BigDecimal referenceValue(Fixture f) {
    return CURRENCIES.stream()
        .map(c -> new BigDecimal(balance(f, c)).multiply(FIXED_WEIGHTS.get(c)))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  ExchangeService.QuoteView convert(Fixture f, String from, String to, String amount) {
    var q = exchange.quote(f.owner(), from, to, new BigDecimal(amount));
    assertFairQuote(q);
    return exchange.execute(f.owner(), q.id());
  }

  Fixture fundedIn(String currency) {
    var f = fixture("35000");
    if (!currency.equals("THB")) convert(f, "THB", currency, "35000");
    return f;
  }

  @Test
  void largeConversionAndSplitReversalCannotMintFunds() {
    var f = fixture("1000000.00");
    var out = exchange.quote(f.owner(), "THB", "USD", new BigDecimal("1000000.00"));
    assertThat(out.receivedAmount()).isEqualTo("28571.42");
    assertFairQuote(out);
    exchange.execute(f.owner(), out.id());
    convert(f, "USD", "THB", "20000.00");
    convert(f, "USD", "THB", "8571.42");
    assertThat(balance(f, "USD")).isEqualTo("0.00");
    assertThat(balance(f, "THB")).isEqualTo("999999.70");
    assertThat(referenceValue(f)).isLessThanOrEqualTo(new BigDecimal("1000000.00"));
    assertThat(banking.history(f.owner(), 0, 10).totalElements()).isEqualTo(4);
  }

  @Test
  void allDirectedPairsSettleAtTheGreatestNonOverpayingMinorUnit() {
    var f = fixture("0");
    for (String from : CURRENCIES)
      for (String to : CURRENCIES) {
        if (from.equals(to)) continue;
        for (String value :
            List.of(
                "0.01",
                "0.35",
                "1.00",
                "35.00",
                "38.00",
                "350.00",
                "9999.99",
                "20000.00",
                "26315.78")) {
          var amount = new BigDecimal(value);
          if (amount.multiply(FIXED_WEIGHTS.get(from)).compareTo(new BigDecimal("1000000")) > 0)
            continue;
          BigDecimal minimumValue = new BigDecimal("0.01").multiply(FIXED_WEIGHTS.get(to));
          if (amount.multiply(FIXED_WEIGHTS.get(from)).compareTo(minimumValue) < 0) {
            assertThatThrownBy(() -> exchange.quote(f.owner(), from, to, amount))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("too small");
          } else {
            var q = exchange.quote(f.owner(), from, to, amount);
            assertThat(new BigDecimal(q.receivedAmount()).scale())
                .isEqualTo(Currency.getInstance(to).getDefaultFractionDigits());
            assertFairQuote(q);
          }
        }
      }
  }

  @Test
  void splittingAllDirectedPairsNeverPaysMoreThanUnsplit() {
    var f = fixture("0");
    for (String from : CURRENCIES)
      for (String to : CURRENCIES) {
        if (from.equals(to)) continue;
        var whole = exchange.quote(f.owner(), from, to, new BigDecimal("1000.00"));
        BigDecimal split = BigDecimal.ZERO;
        for (String part : List.of("333.33", "333.33", "333.34")) {
          var q = exchange.quote(f.owner(), from, to, new BigDecimal(part));
          assertFairQuote(q);
          split = split.add(new BigDecimal(q.receivedAmount()));
        }
        assertThat(split).isLessThanOrEqualTo(new BigDecimal(whole.receivedAmount()));
      }
  }

  @Test
  void allDirectedRoundTripsConserveFixedSnapshotValue() {
    for (String from : CURRENCIES)
      for (String to : CURRENCIES) {
        if (from.equals(to)) continue;
        var f = fundedIn(from);
        BigDecimal start = referenceValue(f);
        var out = convert(f, from, to, "100.00");
        assertThat(referenceValue(f)).isLessThanOrEqualTo(start);
        convert(f, to, from, out.receivedAmount());
        assertThat(referenceValue(f)).isLessThanOrEqualTo(start);
      }
  }

  @Test
  void threeCurrencyCyclesConserveValueAtEveryLeg() {
    for (String first : CURRENCIES)
      for (String second : CURRENCIES) {
        if (first.equals(second)) continue;
        String third =
            CURRENCIES.stream()
                .filter(c -> !c.equals(first) && !c.equals(second))
                .findFirst()
                .orElseThrow();
        var f = fundedIn(first);
        BigDecimal prior = referenceValue(f);
        var a = convert(f, first, second, "100.00");
        assertThat(referenceValue(f)).isLessThanOrEqualTo(prior);
        prior = referenceValue(f);
        var b = convert(f, second, third, a.receivedAmount());
        assertThat(referenceValue(f)).isLessThanOrEqualTo(prior);
        prior = referenceValue(f);
        convert(f, third, first, b.receivedAmount());
        assertThat(referenceValue(f)).isLessThanOrEqualTo(prior);
      }
  }

  @Test
  void tinyConversionsCreateNeitherQuoteNorFinancialChanges() {
    var f = fixture("1");
    for (var pair : List.of(List.of("THB", "USD"), List.of("THB", "EUR"), List.of("USD", "EUR"))) {
      assertThatThrownBy(
              () -> exchange.quote(f.owner(), pair.get(0), pair.get(1), new BigDecimal("0.01")))
          .isInstanceOf(ApiException.class)
          .hasMessageContaining("too small");
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from exchange_quotes where owner_id=?", Long.class, f.owner()))
        .isZero();
    assertThat(balance(f, "THB")).isEqualTo("1.00");
    assertThat(balance(f, "USD")).isEqualTo("0.00");
    assertThat(balance(f, "EUR")).isEqualTo("0.00");
    assertThat(banking.history(f.owner(), 0, 10).totalElements()).isEqualTo(1);
  }

  @Test
  void persistenceExecutionAndReplayUseAmountsNotDisplayRateOrNewProviderData() {
    var f = fixture("1000000");
    var q = exchange.quote(f.owner(), "THB", "USD", new BigDecimal("1000000"));
    assertThat(q.receivedAmount()).isEqualTo("28571.42");
    assertThat(q.rate()).isEqualTo("0.02857143");
    assertThat(
            jdbc.queryForObject(
                "select received_amount from exchange_quotes where id=?", BigDecimal.class, q.id()))
        .isEqualByComparingTo("28571.42");
    assertThat(
            new BigDecimal(q.amount())
                .multiply(new BigDecimal(q.rate()))
                .setScale(2, java.math.RoundingMode.DOWN))
        .isEqualByComparingTo("28571.43");
    org.mockito.Mockito.when(provider.fetch())
        .thenThrow(new IllegalStateException("Provider changed or unavailable"));
    var first = exchange.execute(f.owner(), q.id());
    jdbc.update(
        "update exchange_quotes set expires_at=current_timestamp-interval '1 second' where id=?",
        q.id());
    var repeat = exchange.execute(f.owner(), q.id());
    assertThat(repeat.transactionId()).isEqualTo(first.transactionId());
    assertThat(repeat.receivedAmount()).isEqualTo(q.receivedAmount());
    assertThat(balance(f, "USD")).isEqualTo("28571.42");
    assertThat(balance(f, "THB")).isEqualTo("0.00");
    assertThat(banking.history(f.owner(), 0, 10).totalElements()).isEqualTo(2);
    org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(1)).fetch();
  }

  @Test
  void destinationOverflowRollsBackDebitHistoryAndQuoteExecution() {
    var f = fixture("100");
    jdbc.update(
        "insert into currency_wallets(account_id,currency,balance)"
            + " values(?,'USD',99999999999999999.99)",
        f.account());
    var q = exchange.quote(f.owner(), "THB", "USD", new BigDecimal("35"));
    assertThatThrownBy(() -> exchange.execute(f.owner(), q.id()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("balance limit");
    assertThat(balance(f, "THB")).isEqualTo("100.00");
    assertThat(balance(f, "USD")).isEqualTo("99999999999999999.99");
    assertThat(
            jdbc.queryForObject(
                "select executed_transaction_id from exchange_quotes where id=?",
                UUID.class,
                q.id()))
        .isNull();
    assertThat(banking.history(f.owner(), 0, 10).totalElements()).isEqualTo(1);
    jdbc.update(
        "update currency_wallets set balance=0 where account_id=? and currency='USD'", f.account());
    exchange.execute(f.owner(), q.id());
    assertThat(balance(f, "THB")).isEqualTo("65.00");
    assertThat(balance(f, "USD")).isEqualTo("1.00");
  }

  @Test
  void frozenAccountRejectsNewQuotesAndUnexecutedQuotes() {
    var f = fixture("100");
    var q = exchange.quote(f.owner(), "THB", "USD", new BigDecimal("35"));
    jdbc.update("update bank_accounts set status='FROZEN' where id=?", f.account());
    assertThatThrownBy(() -> exchange.quote(f.owner(), "THB", "USD", new BigDecimal("35")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("frozen");
    assertThatThrownBy(() -> exchange.execute(f.owner(), q.id()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("frozen");
    assertThat(balance(f, "THB")).isEqualTo("100.00");
    assertThat(banking.history(f.owner(), 0, 10).totalElements()).isEqualTo(1);
  }

  @Test
  void distinctQuotesAndWithdrawalCannotOverspendConcurrently() throws Exception {
    var f = fixture("100");
    var usd = exchange.quote(f.owner(), "THB", "USD", new BigDecimal("80"));
    var eur = exchange.quote(f.owner(), "THB", "EUR", new BigDecimal("80"));
    var ready = new CountDownLatch(3);
    var start = new CountDownLatch(1);
    List<Callable<Void>> actions =
        List.of(
            () -> {
              exchange.execute(f.owner(), usd.id());
              return null;
            },
            () -> {
              exchange.execute(f.owner(), eur.id());
              return null;
            },
            () -> {
              banking.withdraw(f.owner(), f.account(), new BigDecimal("80"));
              return null;
            });
    List<Future<Boolean>> results = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(3)) {
      for (var action : actions)
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  try {
                    action.call();
                    return true;
                  } catch (ApiException e) {
                    if (!e.getMessage().startsWith("Insufficient")) throw e;
                    return false;
                  }
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      int success = 0;
      for (var result : results) if (result.get(30, TimeUnit.SECONDS)) success++;
      assertThat(success).isEqualTo(1);
    }
    assertThat(balance(f, "THB")).isEqualTo("20.00");
    assertThat(banking.history(f.owner(), 0, 10).totalElements()).isEqualTo(2);
  }

  void rawProviderFixture() {
    var parser =
        new FrankfurterRateProvider(
            com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build());
    var snapshot =
        parser.parse(
            "[{\"date\":\"2026-10-29\",\"base\":\"EUR\",\"quote\":\"THB\",\"rate\":1},{\"date\":\"2026-10-29\",\"base\":\"EUR\",\"quote\":\"USD\",\"rate\":0.6}]",
            java.time.Instant.parse("2026-10-30T10:00:00Z"));
    org.mockito.Mockito.when(provider.fetch()).thenReturn(snapshot);
  }

  @Test
  void originalProviderPrecisionPreservesExactCentBoundaryConversions() {
    rawProviderFixture();
    var f = fixture("1");
    var q = exchange.quote(f.owner(), "THB", "USD", BigDecimal.ONE);
    assertThat(q.receivedAmount()).isEqualTo("0.60");
    exchange.execute(f.owner(), q.id());
    var back = exchange.quote(f.owner(), "USD", "THB", new BigDecimal("0.60"));
    assertThat(back.receivedAmount()).isEqualTo("1.00");
    exchange.execute(f.owner(), back.id());
    assertThat(balance(f, "THB")).isEqualTo("1.00");
    assertThat(balance(f, "USD")).isEqualTo("0.00");
  }

  @Test
  void originalProviderPrecisionEnforcesExactThbEquivalentLimit() {
    rawProviderFixture();
    var f = fixture("0");
    var boundary = exchange.quote(f.owner(), "USD", "THB", new BigDecimal("600000.00"));
    assertThat(boundary.receivedAmount()).isEqualTo("1000000.00");
    assertThatThrownBy(() -> exchange.quote(f.owner(), "USD", "THB", new BigDecimal("600000.01")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Exchange limit");
  }

  @Test
  void rolloutDrainQueryExcludesExpiredAndExecutedQuotesAndPreservesReplay() {
    var f = fixture("350");
    var executed = exchange.quote(f.owner(), "THB", "USD", BigDecimal.TEN);
    var result = exchange.execute(f.owner(), executed.id());
    var expired = exchange.quote(f.owner(), "THB", "USD", BigDecimal.TEN);
    var open = exchange.quote(f.owner(), "THB", "USD", BigDecimal.TEN);
    jdbc.update(
        "UPDATE exchange_quotes SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id"
            + " IN (?, ?)",
        expired.id(),
        executed.id());
    String filter =
        " FROM exchange_quotes WHERE executed_transaction_id IS NULL AND expires_at >"
            + " CURRENT_TIMESTAMP";
    // Execute the exact runbook SQL against PostgreSQL, then check this owner's eligibility.
    var drain =
        jdbc.queryForMap(
            "SELECT CURRENT_TIMESTAMP AS database_time, count(*) AS outstanding_quotes,"
                + " max(expires_at) AS last_expiry"
                + filter);
    assertThat(((Number) drain.get("outstanding_quotes")).longValue()).isGreaterThanOrEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*)" + filter + " AND owner_id = ?", Long.class, f.owner()))
        .isEqualTo(1L);
    jdbc.update(
        "UPDATE exchange_quotes SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id ="
            + " ?",
        open.id());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*)" + filter + " AND owner_id = ?", Long.class, f.owner()))
        .isZero();
    assertThatThrownBy(() -> exchange.execute(f.owner(), open.id()))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
    assertThat(exchange.execute(f.owner(), executed.id()).transactionId())
        .isEqualTo(result.transactionId());
    assertThat(balance(f, "THB")).isEqualTo("340.00");
  }
}

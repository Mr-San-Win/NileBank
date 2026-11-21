package com.nilebank;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.nilebank.account.*;
import com.nilebank.common.ApiException;
import com.nilebank.exchange.*;
import com.nilebank.transaction.BankingService;
import com.nilebank.user.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class SevenCurrencyTest extends PostgresTest {
  static final Map<String, BigDecimal> WEIGHTS =
      Map.of(
          "THB",
          BigDecimal.ONE,
          "USD",
          new BigDecimal("35"),
          "EUR",
          new BigDecimal("38"),
          "MMK",
          new BigDecimal("0.02"),
          "SGD",
          new BigDecimal("25"),
          "JPY",
          new BigDecimal("0.25"),
          "CNY",
          new BigDecimal("5"));
  @Autowired ExchangeService exchange;
  @Autowired AccountService accounts;
  @Autowired BankingService banking;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean ExchangeRateProvider provider;

  record Fixture(UUID owner, UUID account) {}

  @BeforeEach
  void snapshot() {
    when(provider.fetch())
        .thenReturn(
            new ExchangeRateProvider.Snapshot(
                "Fixed seven-currency test fixture",
                LocalDate.now(),
                Instant.now(),
                WEIGHTS,
                Map.of(),
                SupportedCurrency.codes()));
  }

  Fixture fixture() {
    var user =
        users.saveAndFlush(
            new BankUser("Currency Demo", UUID.randomUUID() + "@example.test", "not-for-auth"));
    var account = accounts.create(user.getId());
    banking.deposit(user.getId(), account.id(), new BigDecimal("10000"));
    return new Fixture(user.getId(), account.id());
  }

  BigDecimal total(Fixture f) {
    return exchange.wallets(f.owner).stream()
        .map(w -> new BigDecimal(w.balance()).multiply(WEIGHTS.get(w.currency())))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  void fair(ExchangeService.QuoteView q) {
    BigDecimal value = new BigDecimal(q.amount()).multiply(WEIGHTS.get(q.fromCurrency()));
    BigDecimal paid = new BigDecimal(q.receivedAmount()), weight = WEIGHTS.get(q.toCurrency());
    assertThat(paid.multiply(weight)).isLessThanOrEqualTo(value);
    assertThat(
            paid.add(BigDecimal.ONE.movePointLeft(SupportedCurrency.scale(q.toCurrency())))
                .multiply(weight))
        .isGreaterThan(value);
  }

  ExchangeService.QuoteView convert(Fixture f, String from, String to, String amount) {
    var q = exchange.quote(f.owner, from, to, new BigDecimal(amount));
    fair(q);
    return exchange.execute(f.owner, q.id());
  }

  @Test
  void sevenWalletsExposeCurrencySpecificPrecision() {
    var f = fixture();
    var wallets = exchange.wallets(f.owner);
    assertThat(wallets).hasSize(7);
    assertThat(wallets.stream().map(ExchangeService.Wallet::currency))
        .containsExactlyElementsOf(SupportedCurrency.codes());
    assertThat(
            wallets.stream()
                .filter(w -> w.currency().equals("JPY"))
                .findFirst()
                .orElseThrow()
                .balance())
        .isEqualTo("0");
    assertThat(SupportedCurrency.scale("JPY")).isZero();
    for (String c : SupportedCurrency.codes())
      if (!c.equals("JPY")) assertThat(SupportedCurrency.scale(c)).isEqualTo(2);
  }

  @Test
  void allFortyTwoDirectedPairsAndRoundTripsCannotIncreaseValue() {
    int pairs = 0;
    for (String from : SupportedCurrency.codes())
      for (String to : SupportedCurrency.codes()) {
        if (from.equals(to)) continue;
        pairs++;
        var f = fixture();
        if (!from.equals("THB")) convert(f, "THB", from, "10000");
        var start = total(f);
        var out = convert(f, from, to, "100");
        assertThat(total(f)).isLessThanOrEqualTo(start);
        var prior = total(f);
        convert(f, to, from, out.receivedAmount());
        assertThat(total(f)).isLessThanOrEqualTo(prior);
      }
    assertThat(pairs).isEqualTo(42);
  }

  @Test
  void splittingAllPairsNeverPaysMoreThanUnsplit() {
    var f = fixture();
    for (String from : SupportedCurrency.codes())
      for (String to : SupportedCurrency.codes()) {
        if (from.equals(to)) continue;
        var whole = exchange.quote(f.owner, from, to, new BigDecimal("100"));
        BigDecimal split = BigDecimal.ZERO;
        for (String part :
            from.equals("JPY") ? List.of("33", "33", "34") : List.of("33.33", "33.33", "33.34")) {
          var q = exchange.quote(f.owner, from, to, new BigDecimal(part));
          fair(q);
          split = split.add(new BigDecimal(q.receivedAmount()));
        }
        assertThat(split).isLessThanOrEqualTo(new BigDecimal(whole.receivedAmount()));
      }
  }

  @Test
  void jpyRoundsDownAndRejectsFractionalSourceWithoutMutation() {
    var f = fixture();
    var q = convert(f, "THB", "JPY", "1.01");
    assertThat(q.receivedAmount()).isEqualTo("4");
    assertThatThrownBy(() -> exchange.quote(f.owner, "JPY", "THB", new BigDecimal("0.50")))
        .isInstanceOf(ApiException.class);
    assertThat(
            jdbc.queryForObject(
                "select balance from currency_wallets where account_id=? and currency='JPY'",
                BigDecimal.class,
                f.account))
        .isEqualByComparingTo("4");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update currency_wallets set balance=4.01 where account_id=? and"
                        + " currency='JPY'",
                    f.account))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void tinyAmountsAndUnavailableProviderNeverCreateQuotesOrFunds() {
    var f = fixture();
    var start = total(f);
    assertThatThrownBy(() -> exchange.quote(f.owner, "MMK", "USD", new BigDecimal("0.01")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("too small");
    when(provider.fetch())
        .thenThrow(
            new ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Unavailable"));
    assertThatThrownBy(() -> exchange.quote(f.owner, "THB", "MMK", BigDecimal.ONE))
        .isInstanceOf(ApiException.class);
    assertThat(total(f)).isEqualByComparingTo(start);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from exchange_quotes where owner_id=?", Long.class, f.owner))
        .isZero();
  }

  @Test
  void ecbSnapshotDoesNotInventMmkQuotes() {
    var f = fixture();
    var codes = SupportedCurrency.codes().stream().filter(c -> !c.equals("MMK")).toList();
    var weights = new HashMap<>(WEIGHTS);
    weights.remove("MMK");
    when(provider.fetch())
        .thenReturn(
            new ExchangeRateProvider.Snapshot(
                "ECB test coverage", LocalDate.now(), Instant.now(), weights, Map.of(), codes));
    assertThat(exchange.rates().rates()).hasSize(6);
    assertThat(exchange.wallets(f.owner)).hasSize(7);
    assertThatThrownBy(() -> exchange.quote(f.owner, "THB", "MMK", BigDecimal.ONE))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));
  }

  @Test
  void concurrentJpyQuoteReplayCreditsOnlyOnce() throws Exception {
    var f = fixture();
    var q = exchange.quote(f.owner, "THB", "JPY", new BigDecimal("100"));
    var ready = new CountDownLatch(6);
    var start = new CountDownLatch(1);
    Set<UUID> ids = new HashSet<>();
    try (var pool = Executors.newFixedThreadPool(6)) {
      List<Future<UUID>> results = new ArrayList<>();
      for (int i = 0; i < 6; i++)
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return exchange.execute(f.owner, q.id()).transactionId();
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (var result : results) ids.add(result.get(30, TimeUnit.SECONDS));
    }
    assertThat(ids).hasSize(1);
    assertThat(
            exchange.wallets(f.owner).stream()
                .filter(w -> w.currency().equals("JPY"))
                .findFirst()
                .orElseThrow()
                .balance())
        .isEqualTo("400");
    assertThat(banking.history(f.owner, 0, 10).totalElements()).isEqualTo(2);
    assertThat(
            banking.history(f.owner, 0, 10).content().stream()
                .filter(t -> t.type() == com.nilebank.transaction.BankingTransaction.Type.EXCHANGE)
                .findFirst()
                .orElseThrow()
                .description())
        .contains("Fixed seven-currency test fixture");
  }

  @Test
  void destinationOverflowRollsBackJpyExchangeDebitAndAudit() {
    var f = fixture();
    jdbc.update(
        "insert into currency_wallets(account_id,currency,balance)"
            + " values(?,'JPY',99999999999999999)",
        f.account);
    var q = exchange.quote(f.owner, "THB", "JPY", BigDecimal.ONE);
    assertThatThrownBy(() -> exchange.execute(f.owner, q.id())).isInstanceOf(ApiException.class);
    assertThat(accounts.mine(f.owner).balance()).isEqualByComparingTo("10000");
    assertThat(
            jdbc.queryForObject(
                "select executed_transaction_id from exchange_quotes where id=?",
                UUID.class,
                q.id()))
        .isNull();
    assertThat(banking.history(f.owner, 0, 10).totalElements()).isEqualTo(1);
  }

  @Test
  void fullSevenCurrencyCyclesConserveReferenceValueAtEveryLeg() {
    for (boolean reverse : List.of(false, true)) {
      var sequence = new ArrayList<>(SupportedCurrency.codes());
      if (reverse) java.util.Collections.reverse(sequence);
      var f = fixture();
      if (!sequence.getFirst().equals("THB")) convert(f, "THB", sequence.getFirst(), "10000");
      for (int i = 0; i < sequence.size(); i++) {
        String from = sequence.get(i), to = sequence.get((i + 1) % sequence.size());
        String available =
            exchange.wallets(f.owner).stream()
                .filter(w -> w.currency().equals(from))
                .findFirst()
                .orElseThrow()
                .balance();
        var before = total(f);
        convert(f, from, to, available);
        assertThat(total(f)).isLessThanOrEqualTo(before);
      }
    }
  }

  @Test
  void snapshotCurrencyDeclarationsAreValidatedAndCopied() {
    for (List<String> invalid :
        Arrays.asList(null, List.<String>of(), List.of("THB", "XYZ"), List.of("THB", "THB"))) {
      assertThatThrownBy(
              () ->
                  new ExchangeRateProvider.Snapshot(
                      "invalid fixture",
                      LocalDate.now(),
                      Instant.now(),
                      WEIGHTS,
                      Map.of(),
                      invalid))
          .isInstanceOf(ApiException.class);
    }
    var declared = new ArrayList<>(SupportedCurrency.codes());
    var snapshot =
        new ExchangeRateProvider.Snapshot(
            "copied fixture", LocalDate.now(), Instant.now(), WEIGHTS, Map.of(), declared);
    declared.clear();
    assertThat(snapshot.currencies()).hasSize(7);
    assertThatThrownBy(() -> snapshot.currencies().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}

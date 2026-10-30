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
}

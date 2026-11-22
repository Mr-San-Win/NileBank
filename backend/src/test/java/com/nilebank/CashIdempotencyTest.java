package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.nilebank.account.*;
import com.nilebank.common.ApiException;
import com.nilebank.transaction.*;
import com.nilebank.user.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CashIdempotencyTest extends PostgresTest {
  @Autowired BankingService banking;
  @Autowired AccountService accounts;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  record Fixture(UUID owner, UUID account) {}

  Fixture fixture() {
    var owner =
        users
            .saveAndFlush(
                new BankUser("Cash Demo", UUID.randomUUID() + "@example.test", "not-for-auth"))
            .getId();
    return new Fixture(owner, accounts.create(owner).id());
  }

  @Test
  void retriesReturnOriginalTransactionAndRejectChangedPayload() {
    var f = fixture();
    var first = banking.deposit(f.owner, f.account, new BigDecimal("100"), "cash-deposit-key");
    assertThat(banking.deposit(f.owner, f.account, new BigDecimal("100.00"), "cash-deposit-key"))
        .isEqualTo(first);
    assertThatThrownBy(
            () -> banking.deposit(f.owner, f.account, new BigDecimal("101"), "cash-deposit-key"))
        .isInstanceOf(ApiException.class);
    var out = banking.withdraw(f.owner, f.account, new BigDecimal("10"), "cash-withdraw-key");
    assertThat(banking.withdraw(f.owner, f.account, new BigDecimal("10"), "cash-withdraw-key"))
        .isEqualTo(out);
    assertThat(accounts.mine(f.owner).balance()).isEqualByComparingTo("90");
    assertThat(banking.history(f.owner, 0, 10).totalElements()).isEqualTo(2);
  }

  @Test
  void concurrentRetriesMoveMoneyOnce() throws Exception {
    var f = fixture();
    var start = new CountDownLatch(1);
    Set<UUID> ids = new HashSet<>();
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Future<UUID>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return banking
                      .deposit(f.owner, f.account, new BigDecimal("10"), "parallel-cash-key")
                      .id();
                }));
      start.countDown();
      for (var r : results) ids.add(r.get(30, TimeUnit.SECONDS));
    }
    assertThat(ids).hasSize(1);
    assertThat(accounts.mine(f.owner).balance()).isEqualByComparingTo("10");
  }

  @Test
  void failureDoesNotConsumeKeyAndReplaySurvivesFreeze() {
    var f = fixture();
    assertThatThrownBy(
            () -> banking.withdraw(f.owner, f.account, new BigDecimal("1"), "retry-failed-key"))
        .isInstanceOf(ApiException.class);
    banking.deposit(f.owner, f.account, new BigDecimal("10"));
    var out = banking.withdraw(f.owner, f.account, new BigDecimal("1"), "retry-failed-key");
    jdbc.update("update bank_accounts set status='FROZEN' where id=?", f.account);
    assertThat(banking.withdraw(f.owner, f.account, new BigDecimal("1"), "retry-failed-key"))
        .isEqualTo(out);
    assertThatThrownBy(
            () -> banking.deposit(f.owner, f.account, new BigDecimal("1"), "fresh-frozen-key"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void rollbackRemovesBalanceAuditAndRetryRecordTogether() {
    var f = fixture();
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      banking.deposit(
                          f.owner, f.account, new BigDecimal("10"), "rollback-cash-key");
                      throw new IllegalStateException("Simulated downstream failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(accounts.mine(f.owner).balance()).isZero();
    assertThat(banking.history(f.owner, 0, 10).totalElements()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from cash_requests where account_id=?", Long.class, f.account))
        .isZero();
    banking.deposit(f.owner, f.account, new BigDecimal("10"), "rollback-cash-key");
    assertThat(accounts.mine(f.owner).balance()).isEqualByComparingTo("10");
  }

  @Test
  void namespacesOwnershipValidationAndLegacyCallsRemainCorrect() {
    var f = fixture();
    var other = fixture();
    banking.deposit(f.owner, f.account, new BigDecimal("10"), "shared-cash-key");
    banking.withdraw(f.owner, f.account, new BigDecimal("1"), "shared-cash-key");
    assertThatThrownBy(
            () -> banking.deposit(other.owner, f.account, new BigDecimal("1"), "shared-cash-key"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> banking.deposit(f.owner, f.account, new BigDecimal("1"), "short"))
        .isInstanceOf(ApiException.class);
    banking.deposit(f.owner, f.account, new BigDecimal("1"));
    banking.deposit(f.owner, f.account, new BigDecimal("1"));
    assertThat(accounts.mine(f.owner).balance()).isEqualByComparingTo("11");
  }
}

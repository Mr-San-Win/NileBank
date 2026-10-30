package com.vaultbank;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vaultbank.account.*;
import com.vaultbank.common.ApiException;
import com.vaultbank.transaction.*;
import com.vaultbank.user.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class BankingTest extends PostgresTest {
  @Autowired AccountService accounts;
  @Autowired BankingService banking;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  AccountService.AccountView account() {
    var user =
        users.saveAndFlush(
            new BankUser(
                "Demo Student",
                UUID.randomUUID() + "@example.test",
                "not-used-for-authentication"));
    return accounts.create(user.getId());
  }

  UUID owner(UUID accountId) {
    return jdbc.queryForObject(
        "select owner_id from bank_accounts where id=?", UUID.class, accountId);
  }

  BigDecimal balance(UUID owner) {
    return accounts.mine(owner).balance();
  }

  @Test
  void newAccountIsZeroAndOnlyOnePerUser() {
    var a = account();
    var owner = owner(a.id());
    assertThat(a.balance()).isEqualByComparingTo("0.00");
    assertThatThrownBy(() -> accounts.create(owner))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("already");
  }

  @Test
  void depositsAndWithdrawalsPersistHistory() {
    var a = account();
    var owner = owner(a.id());
    banking.deposit(owner, a.id(), new BigDecimal("100.50"));
    banking.withdraw(owner, a.id(), new BigDecimal("20.25"));
    assertThat(balance(owner)).isEqualByComparingTo("80.25");
    var history = banking.history(owner, 0, 10);
    assertThat(history.totalElements()).isEqualTo(2);
    assertThat(history.content())
        .extracting(TransactionView::type)
        .containsExactly(BankingTransaction.Type.WITHDRAWAL, BankingTransaction.Type.DEPOSIT);
    assertThat(banking.history(owner, 0, 1).totalPages()).isEqualTo(2);
  }

  @Test
  void invalidAmountsAreRejectedInServiceAndNeverRounded() {
    var a = account();
    var owner = owner(a.id());
    for (String amount : List.of("0", "-1", "0.001", "1000000.01", "1.000"))
      assertThatThrownBy(() -> banking.deposit(owner, a.id(), new BigDecimal(amount)))
          .isInstanceOf(ApiException.class);
    assertThat(balance(owner)).isZero();
    assertThat(banking.history(owner, 0, 10).totalElements()).isZero();
  }

  @Test
  void insufficientFundsDoNotChangeBalanceOrHistory() {
    var a = account();
    var owner = owner(a.id());
    banking.deposit(owner, a.id(), new BigDecimal("10.00"));
    assertThatThrownBy(() -> banking.withdraw(owner, a.id(), new BigDecimal("10.01")))
        .isInstanceOf(ApiException.class)
        .hasMessage("Insufficient funds");
    assertThat(balance(owner)).isEqualByComparingTo("10.00");
    assertThat(banking.history(owner, 0, 10).totalElements()).isEqualTo(1);
  }

  @Test
  void servicesAndApiEnforceOwnership() throws Exception {
    var a = account();
    var stranger = owner(account().id());
    assertThatThrownBy(() -> banking.deposit(stranger, a.id(), BigDecimal.ONE))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> banking.withdraw(stranger, a.id(), BigDecimal.ONE))
        .isInstanceOf(ApiException.class);
    mvc.perform(
            post("/api/v1/transactions/deposit")
                .with(user(stranger.toString()))
                .with(csrf())
                .contentType("application/json")
                .content("{\"accountId\":\"" + a.id() + "\",\"amount\":1}"))
        .andExpect(status().isNotFound());
    assertThat(balance(owner(a.id()))).isZero();
    assertThat(accounts.mine(stranger).id()).isNotEqualTo(a.id());
  }

  @Test
  void frozenAccountsAndMissingCsrfRejectOperations() throws Exception {
    var a = account();
    var owner = owner(a.id());
    jdbc.update("update bank_accounts set status='FROZEN' where id=?", a.id());
    assertThatThrownBy(() -> banking.deposit(owner, a.id(), BigDecimal.ONE))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("frozen");
    mvc.perform(
            post("/api/v1/transactions/deposit")
                .with(user(owner.toString()))
                .contentType("application/json")
                .content("{\"accountId\":\"" + a.id() + "\",\"amount\":1}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void simultaneousWithdrawalsCannotOverspend() throws Exception {
    var a = account();
    var owner = owner(a.id());
    banking.deposit(owner, a.id(), new BigDecimal("100.00"));
    int workers = 12;
    var ready = new CountDownLatch(workers);
    var start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(workers)) {
      for (int i = 0; i < workers; i++)
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("start timed out");
                  try {
                    banking.withdraw(owner, a.id(), new BigDecimal("80.00"));
                    return true;
                  } catch (ApiException e) {
                    if (!e.getMessage().equals("Insufficient funds")) throw e;
                    return false;
                  }
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      int successes = 0;
      for (var result : results) if (result.get(30, TimeUnit.SECONDS)) successes++;
      assertThat(successes).isEqualTo(1);
    }
    assertThat(balance(owner)).isEqualByComparingTo("20.00");
    assertThat(banking.history(owner, 0, 100).totalElements()).isEqualTo(2);
  }

  @Test
  void apiValidationAndPaginationBounds() throws Exception {
    var a = account();
    var owner = owner(a.id());
    mvc.perform(
            post("/api/v1/transactions/deposit")
                .with(user(owner.toString()))
                .with(csrf())
                .contentType("application/json")
                .content("{\"accountId\":\"" + a.id() + "\",\"amount\":0.001}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.amount").exists());
    mvc.perform(get("/api/v1/transactions?size=101").with(user(owner.toString())))
        .andExpect(status().isBadRequest());
  }
}

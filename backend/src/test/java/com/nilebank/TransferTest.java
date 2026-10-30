package com.nilebank;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nilebank.account.*;
import com.nilebank.common.ApiException;
import com.nilebank.transaction.*;
import com.nilebank.transfer.TransferService;
import com.nilebank.user.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class TransferTest extends PostgresTest {
  @Autowired AccountService accounts;
  @Autowired BankingService banking;
  @Autowired TransferService transfers;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  record Fixture(UUID owner, AccountService.AccountView account) {}

  Fixture account() {
    var user =
        users.saveAndFlush(
            new BankUser(
                "Demo Student",
                UUID.randomUUID() + "@example.test",
                "not-used-for-authentication"));
    return new Fixture(user.getId(), accounts.create(user.getId()));
  }

  BigDecimal balance(Fixture f) {
    return accounts.mine(f.owner()).balance();
  }

  TransactionView send(Fixture from, Fixture to, String amount, String key) {
    return transfers.transfer(
        from.owner(),
        from.account().id(),
        to.account().accountNumber(),
        new BigDecimal(amount),
        key);
  }

  @Test
  void transferUpdatesBothBalancesAndHistory() {
    var a = account();
    var b = account();
    banking.deposit(a.owner(), a.account().id(), new BigDecimal("100"));
    var t = send(a, b, "30.25", "valid-key");
    assertThat(balance(a)).isEqualByComparingTo("69.75");
    assertThat(balance(b)).isEqualByComparingTo("30.25");
    assertThat(t.reference()).startsWith("NB-");
    assertThat(banking.history(a.owner(), 0, 10).content().getFirst().direction()).isEqualTo("OUT");
    assertThat(banking.history(b.owner(), 0, 10).content().getFirst().direction()).isEqualTo("IN");
  }

  @Test
  void insufficientFundsLeavesBothAccountsUnchanged() {
    var a = account();
    var b = account();
    assertThatThrownBy(() -> send(a, b, "1", "no-funds-key"))
        .isInstanceOf(ApiException.class)
        .hasMessage("Insufficient funds");
    assertThat(balance(a)).isZero();
    assertThat(balance(b)).isZero();
    assertThat(banking.history(a.owner(), 0, 10).totalElements()).isZero();
  }

  @Test
  void creditFailureAfterDebitRollsBackEntireTransactionAndKey() {
    var a = account();
    var b = account();
    banking.deposit(a.owner(), a.account().id(), new BigDecimal("10"));
    jdbc.update(
        "update bank_accounts set balance=99999999999999999.99 where id=?", b.account().id());
    assertThatThrownBy(() -> send(a, b, "1", "rollback-key"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("balance limit");
    assertThat(balance(a)).isEqualByComparingTo("10");
    assertThat(balance(b)).isEqualByComparingTo("99999999999999999.99");
    assertThat(banking.history(a.owner(), 0, 10).totalElements()).isEqualTo(1);
    jdbc.update("update bank_accounts set balance=0 where id=?", b.account().id());
    send(a, b, "1", "rollback-key");
    assertThat(balance(a)).isEqualByComparingTo("9");
    assertThat(balance(b)).isEqualByComparingTo("1");
  }

  @Test
  void sameKeyReplaysButChangedPayloadConflicts() {
    var a = account();
    var b = account();
    var c = account();
    banking.deposit(a.owner(), a.account().id(), new BigDecimal("100"));
    var original = send(a, b, "20", "repeat-key");
    assertThat(send(a, b, "20.00", "repeat-key").id()).isEqualTo(original.id());
    assertThatThrownBy(() -> send(a, b, "21", "repeat-key"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("different");
    assertThatThrownBy(() -> send(a, c, "20", "repeat-key"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("different");
    assertThat(balance(a)).isEqualByComparingTo("80");
    assertThat(balance(b)).isEqualByComparingTo("20");
    assertThat(balance(c)).isZero();
  }

  @Test
  void ownershipSelfTransferFrozenRecipientAndUnknownRecipientAreRejected() throws Exception {
    var a = account();
    var b = account();
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    b.owner(),
                    a.account().id(),
                    b.account().accountNumber(),
                    BigDecimal.ONE,
                    "stranger-key"))
        .isInstanceOf(ApiException.class)
        .hasMessage("Account not found");
    assertThatThrownBy(() -> send(a, a, "1", "self-transfer")).isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    a.owner(),
                    a.account().id(),
                    "NB00000000000000000000",
                    BigDecimal.ONE,
                    "unknown-key"))
        .isInstanceOf(ApiException.class);
    banking.deposit(a.owner(), a.account().id(), BigDecimal.TEN);
    jdbc.update("update bank_accounts set status='FROZEN' where id=?", b.account().id());
    assertThatThrownBy(() -> send(a, b, "1", "frozen-key"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("frozen");
    assertThat(balance(a)).isEqualByComparingTo("10");
    mvc.perform(
            post("/api/v1/transfers")
                .with(user(a.owner().toString()))
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"sourceAccountId\":\""
                        + a.account().id()
                        + "\",\"recipientAccountNumber\":\""
                        + b.account().accountNumber()
                        + "\",\"amount\":1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void simultaneousDuplicateKeysMoveMoneyOnce() throws Exception {
    var a = account();
    var b = account();
    banking.deposit(a.owner(), a.account().id(), new BigDecimal("100"));
    var ids = concurrent(8, i -> send(a, b, "25", "concurrent-key").id());
    assertThat(new HashSet<>(ids)).hasSize(1);
    assertThat(balance(a)).isEqualByComparingTo("75");
    assertThat(balance(b)).isEqualByComparingTo("25");
    assertThat(banking.history(a.owner(), 0, 100).totalElements()).isEqualTo(2);
  }

  @Test
  void opposingSimultaneousTransfersPreserveTotalMoney() throws Exception {
    var a = account();
    var b = account();
    banking.deposit(a.owner(), a.account().id(), new BigDecimal("100"));
    banking.deposit(b.owner(), b.account().id(), new BigDecimal("100"));
    concurrent(
        16,
        i ->
            (i % 2 == 0 ? send(a, b, "3.25", "opposing-" + i) : send(b, a, "3.25", "opposing-" + i))
                .id());
    assertThat(balance(a).add(balance(b))).isEqualByComparingTo("200");
    assertThat(balance(a)).isEqualByComparingTo("100");
    assertThat(balance(b)).isEqualByComparingTo("100");
    assertThat(banking.history(a.owner(), 0, 100).totalElements()).isEqualTo(17);
  }

  @Test
  void simultaneousTransfersAndWithdrawalsCannotOverspend() throws Exception {
    var a = account();
    var b = account();
    banking.deposit(a.owner(), a.account().id(), new BigDecimal("100"));
    var results =
        concurrent(
            12,
            i -> {
              try {
                if (i % 2 == 0) send(a, b, "80", "mixed-key-" + i);
                else banking.withdraw(a.owner(), a.account().id(), new BigDecimal("80"));
                return true;
              } catch (ApiException e) {
                if (!e.getMessage().equals("Insufficient funds")) throw e;
                return false;
              }
            });
    assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    assertThat(balance(a)).isEqualByComparingTo("20");
    assertThat(balance(b)).isIn(new BigDecimal("0.00"), new BigDecimal("80.00"));
  }

  <T> List<T> concurrent(int count, java.util.function.IntFunction<T> operation) throws Exception {
    var ready = new CountDownLatch(count);
    var start = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>();
    List<T> results = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(count)) {
      for (int i = 0; i < count; i++) {
        final int index = i;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("start timed out");
                  return operation.apply(index);
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (var future : futures) results.add(future.get(45, TimeUnit.SECONDS));
    }
    return results;
  }
}

package com.nilebank;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.account.*;
import com.nilebank.common.ApiException;
import com.nilebank.finance.FinanceService;
import com.nilebank.savings.*;
import com.nilebank.transaction.*;
import com.nilebank.user.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class SavingsTest extends PostgresTest {
  @Autowired SavingsService savings;
  @Autowired AccountService accounts;
  @Autowired BankingService banking;
  @Autowired FinanceService finance;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  record Fixture(UUID owner, UUID main) {}

  Fixture fixture() {
    var owner =
        users
            .saveAndFlush(
                new BankUser("Savings Demo", UUID.randomUUID() + "@example.test", "not-for-auth"))
            .getId();
    var account = accounts.create(owner);
    banking.deposit(owner, account.id(), new BigDecimal("100"));
    return new Fixture(owner, account.id());
  }

  SavingsService.AccountView account(Fixture f) {
    return savings.create(f.owner, "Emergency fund", new BigDecimal("1000"));
  }

  BigDecimal main(Fixture f) {
    return accounts.mine(f.owner).balance();
  }

  BigDecimal saved(Fixture f, UUID id) {
    return new BigDecimal(
        savings.list(f.owner).stream()
            .filter(a -> a.id().equals(id))
            .findFirst()
            .orElseThrow()
            .balance());
  }

  BigDecimal total(Fixture f) {
    return main(f)
        .add(
            savings.list(f.owner).stream()
                .map(a -> new BigDecimal(a.balance()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  SavingsService.MovementView move(
      Fixture f, UUID id, String direction, String amount, String key) {
    return savings.move(f.owner, id, direction, new BigDecimal(amount), key);
  }

  @Test
  void newSavingsHasZeroBalanceAndDoesNotConvertPlanningAllocationsIntoMoney() {
    var f = fixture();
    var goal = finance.goal(f.owner, "Emergency fund", new BigDecimal("1000"));
    finance.contribute(f.owner, goal.id(), new BigDecimal("400"));
    var savings = account(f);
    assertThat(savings.balance()).isEqualTo("0.00");
    assertThat(savings.currency()).isEqualTo("THB");
    assertThat(savings.status()).isEqualTo("ACTIVE");
    assertThat(savings.target()).isEqualTo("1000.00");
    assertThat(main(f)).isEqualByComparingTo("100");
    assertThat(total(f)).isEqualByComparingTo("100");
  }

  @Test
  void inAndOutAreAtomicAuditedAndConserveMoney() {
    var f = fixture();
    var account = account(f);
    var in = move(f, account.id(), "IN", "40", "savings-in-key");
    var out = move(f, account.id(), "OUT", "15", "savings-out-key");
    assertThat(main(f)).isEqualByComparingTo("75");
    assertThat(saved(f, account.id())).isEqualByComparingTo("25");
    assertThat(total(f)).isEqualByComparingTo("100");
    assertThat(in.mainBalanceAfter()).isEqualTo("60.00");
    assertThat(out.savingsBalanceAfter()).isEqualTo("25.00");
    assertThat(banking.history(f.owner, 0, 10).content().stream().map(TransactionView::type))
        .contains(BankingTransaction.Type.SAVINGS_IN, BankingTransaction.Type.SAVINGS_OUT);
    assertThat(savings.history(f.owner, account.id(), 0, 10).totalElements()).isEqualTo(2);
  }

  @Test
  void ownershipIsEnforcedForReadWriteAndHistory() {
    var a = fixture();
    var b = fixture();
    var account = account(a);
    assertThat(savings.list(b.owner)).isEmpty();
    assertThatThrownBy(() -> move(b, account.id(), "IN", "10", "cross-owner-key"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> savings.edit(b.owner, account.id(), "Stolen", null, "ACTIVE"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> savings.history(b.owner, account.id(), 0, 10))
        .isInstanceOf(ApiException.class);
    assertThat(main(b)).isEqualByComparingTo("100");
  }

  @Test
  void failedMovementDoesNotConsumeKeyOrLeavePartialChanges() {
    var f = fixture();
    var account = account(f);
    assertThatThrownBy(() -> move(f, account.id(), "IN", "101", "retry-savings-key"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> move(f, account.id(), "OUT", "1", "withdraw-empty-key"))
        .isInstanceOf(ApiException.class);
    assertThat(main(f)).isEqualByComparingTo("100");
    assertThat(saved(f, account.id())).isZero();
    banking.deposit(f.owner, f.main, new BigDecimal("5"));
    move(f, account.id(), "IN", "101", "retry-savings-key");
    assertThat(total(f)).isEqualByComparingTo("105");
    assertThat(savings.history(f.owner, account.id(), 0, 10).totalElements()).isEqualTo(1);
  }

  @Test
  void replayReturnsOriginalResultAndChangedParametersConflict() {
    var f = fixture();
    var a = account(f);
    var b = savings.create(f.owner, "Tuition", null);
    var original = move(f, a.id(), "IN", "10", "same-savings-key");
    assertThat(move(f, a.id(), "IN", "10.00", "same-savings-key")).isEqualTo(original);
    assertThatThrownBy(() -> move(f, a.id(), "IN", "11", "same-savings-key"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> move(f, a.id(), "OUT", "10", "same-savings-key"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> move(f, b.id(), "IN", "10", "same-savings-key"))
        .isInstanceOf(ApiException.class);
    assertThat(total(f)).isEqualByComparingTo("100");
    assertThat(saved(f, a.id())).isEqualByComparingTo("10");
  }

  @Test
  void frozenAccountsRejectNewMovesButCompletedRequestsStillReplay() {
    var f = fixture();
    var a = account(f);
    var original = move(f, a.id(), "IN", "10", "frozen-replay-key");
    savings.edit(f.owner, a.id(), a.name(), new BigDecimal("1000"), "FROZEN");
    assertThatThrownBy(() -> move(f, a.id(), "OUT", "1", "frozen-new-key"))
        .isInstanceOf(ApiException.class);
    assertThat(move(f, a.id(), "IN", "10", "frozen-replay-key")).isEqualTo(original);
    savings.edit(f.owner, a.id(), a.name(), null, "ACTIVE");
    jdbc.update("update bank_accounts set status='FROZEN' where id=?", f.main);
    assertThatThrownBy(() -> move(f, a.id(), "OUT", "1", "parent-frozen-key"))
        .isInstanceOf(ApiException.class);
    assertThat(move(f, a.id(), "IN", "10", "frozen-replay-key")).isEqualTo(original);
  }

  @Test
  void concurrentDuplicateKeysMoveFundsExactlyOnce() throws Exception {
    var f = fixture();
    var a = account(f);
    var start = new CountDownLatch(1);
    Set<UUID> ids = new HashSet<>();
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Future<UUID>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return move(f, a.id(), "IN", "10", "parallel-save-key").transactionId();
                }));
      start.countDown();
      for (var result : results) ids.add(result.get(30, TimeUnit.SECONDS));
    }
    assertThat(ids).hasSize(1);
    assertThat(main(f)).isEqualByComparingTo("90");
    assertThat(saved(f, a.id())).isEqualByComparingTo("10");
    assertThat(total(f)).isEqualByComparingTo("100");
  }

  @Test
  void concurrentSavingsAndWithdrawalCannotOverspend() throws Exception {
    var f = fixture();
    var a = account(f);
    var b = savings.create(f.owner, "Tuition", null);
    var start = new CountDownLatch(1);
    List<Callable<Boolean>> operations =
        List.of(
            () -> {
              move(f, a.id(), "IN", "80", "compete-save-one");
              return false;
            },
            () -> {
              move(f, b.id(), "IN", "80", "compete-save-two");
              return false;
            },
            () -> {
              banking.withdraw(f.owner, f.main, new BigDecimal("80"));
              return true;
            });
    int success = 0;
    boolean withdrew = false;
    try (var pool = Executors.newFixedThreadPool(3)) {
      List<Future<Optional<Boolean>>> results = new ArrayList<>();
      for (var operation : operations)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    return Optional.of(operation.call());
                  } catch (ApiException e) {
                    if (!e.getMessage().startsWith("Insufficient")) throw e;
                    return Optional.empty();
                  }
                }));
      start.countDown();
      for (var result : results) {
        var value = result.get(30, TimeUnit.SECONDS);
        if (value.isPresent()) {
          success++;
          withdrew = value.get();
        }
      }
    }
    assertThat(success).isEqualTo(1);
    assertThat(main(f)).isEqualByComparingTo("20");
    assertThat(total(f)).isEqualByComparingTo(withdrew ? "20" : "100");
  }

  @Test
  void opposingSavingsMovesPreserveTotalWithoutDeadlock() throws Exception {
    var f = fixture();
    var a = account(f);
    move(f, a.id(), "IN", "50", "seed-saving-key");
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(8)) {
      List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        int index = i;
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return move(
                      f, a.id(), index % 2 == 0 ? "IN" : "OUT", "10", "opposing-save-" + index);
                }));
      }
      start.countDown();
      for (var result : results) result.get(30, TimeUnit.SECONDS);
    }
    assertThat(main(f)).isEqualByComparingTo("50");
    assertThat(saved(f, a.id())).isEqualByComparingTo("50");
    assertThat(total(f)).isEqualByComparingTo("100");
  }

  @Test
  void destinationOverflowRollsBackBothDirectionsAndAudit() {
    var f = fixture();
    var a = account(f);
    jdbc.update("update savings_accounts set balance=99999999999999999.99 where id=?", a.id());
    assertThatThrownBy(() -> move(f, a.id(), "IN", "1", "save-overflow-key"))
        .isInstanceOf(ApiException.class);
    assertThat(main(f)).isEqualByComparingTo("100");
    jdbc.update("update savings_accounts set balance=10 where id=?", a.id());
    jdbc.update("update bank_accounts set balance=99999999999999999.99 where id=?", f.main);
    assertThatThrownBy(() -> move(f, a.id(), "OUT", "1", "main-overflow-key"))
        .isInstanceOf(ApiException.class);
    assertThat(saved(f, a.id())).isEqualByComparingTo("10");
    assertThat(savings.history(f.owner, a.id(), 0, 10).totalElements()).isZero();
    assertThat(banking.history(f.owner, 0, 10).totalElements()).isEqualTo(1);
  }

  @Test
  void movementHistoryCannotBeChangedOrDeletedEvenThroughSql() {
    var f = fixture();
    var a = account(f);
    var movement = move(f, a.id(), "IN", "10", "immutable-save-key");
    assertThatThrownBy(
            () -> jdbc.update("update savings_movements set amount=99 where id=?", movement.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("delete from savings_movements where id=?", movement.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(savings.history(f.owner, a.id(), 0, 10).totalElements()).isEqualTo(1);
  }

  @Test
  void concurrentCreationHonorsTenAccountLimit() throws Exception {
    var f = fixture();
    var start = new CountDownLatch(1);
    int created = 0;
    try (var pool = Executors.newFixedThreadPool(12)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        int index = i;
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    savings.create(f.owner, "Savings " + index, null);
                    return true;
                  } catch (ApiException e) {
                    if (!e.getMessage().contains("10 savings")) throw e;
                    return false;
                  }
                }));
      }
      start.countDown();
      for (var result : results) if (result.get(30, TimeUnit.SECONDS)) created++;
    }
    assertThat(created).isEqualTo(10);
    assertThat(savings.list(f.owner)).hasSize(10);
    assertThat(total(f)).isEqualByComparingTo("100");
  }

  @Test
  void targetProgressUsesActualBalanceAndCanExceedTarget() {
    var f = fixture();
    var a = savings.create(f.owner, "Small target", new BigDecimal("5"));
    move(f, a.id(), "IN", "10", "target-save-key");
    var view = savings.list(f.owner).getFirst();
    assertThat(view.percent()).isEqualTo("200");
    assertThat(view.remaining()).isEqualTo("0.00");
  }

  @Test
  void historyIsPaginatedAndInvalidInputsAreControlled() {
    var f = fixture();
    var a = account(f);
    for (int i = 0; i < 3; i++) move(f, a.id(), "IN", "1", "paging-save-" + i);
    var page = savings.history(f.owner, a.id(), 1, 2);
    assertThat(page.content()).hasSize(1);
    assertThat(page.totalElements()).isEqualTo(3);
    assertThat(page.totalPages()).isEqualTo(2);
    assertThatThrownBy(() -> savings.history(f.owner, a.id(), -1, 20))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> savings.history(f.owner, a.id(), 0, 101))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> savings.create(f.owner, " ", null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> savings.move(f.owner, a.id(), "IN", new BigDecimal("0.001"), "invalid-precision"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> savings.move(f.owner, a.id(), "OTHER", BigDecimal.ONE, "invalid-direction"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> savings.move(f.owner, a.id(), "IN", BigDecimal.ONE, "bad"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void apiRequiresSessionsCsrfAndRetryKeysAndReturnsDecimalStrings() throws Exception {
    mvc.perform(get("/api/v1/savings/accounts")).andExpect(status().isUnauthorized());
    var f = fixture();
    mvc.perform(
            post("/api/v1/savings/accounts")
                .with(user(f.owner.toString()))
                .contentType("application/json")
                .content("{\"name\":\"API savings\"}"))
        .andExpect(status().isForbidden());
    var result =
        mvc.perform(
                post("/api/v1/savings/accounts")
                    .with(user(f.owner.toString()))
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"name\":\"API savings\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.balance").value("0.00"))
            .andReturn();
    String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    String body =
        json.writeValueAsString(Map.of("savingsId", id, "direction", "IN", "amount", "10.00"));
    mvc.perform(
            post("/api/v1/savings/movements")
                .with(user(f.owner.toString()))
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/savings/movements")
                .with(user(f.owner.toString()))
                .with(csrf())
                .header("Idempotency-Key", "api-savings-key")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value("10.00"))
        .andExpect(jsonPath("$.savingsBalanceAfter").value("10.00"));
  }
}

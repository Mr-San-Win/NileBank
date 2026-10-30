package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.nilebank.account.*;
import com.nilebank.common.ApiException;
import com.nilebank.finance.*;
import com.nilebank.transaction.BankingService;
import com.nilebank.user.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FinanceTest extends PostgresTest {
  @Autowired FinanceService finance;
  @Autowired UserRepository users;
  @Autowired AccountService accounts;
  @Autowired BankingService banking;

  UUID owner() {
    return users
        .saveAndFlush(
            new BankUser("Finance Student", UUID.randomUUID() + "@example.test", "not-for-auth"))
        .getId();
  }

  LocalDate today() {
    return LocalDate.now(ZoneId.of("Asia/Bangkok"));
  }

  String month() {
    return YearMonth.from(today()).toString();
  }

  void entry(UUID owner, String direction, String category, String amount) {
    finance.add(owner, direction, category, new BigDecimal(amount), "Test entry", today());
  }

  @Test
  void totalsBudgetsAndSavingsRateAreCalculatedFromRecordedEntries() {
    var o = owner();
    entry(o, "INCOME", "SALARY", "20000");
    entry(o, "EXPENSE", "HOUSING", "10000");
    entry(o, "EXPENSE", "FOOD", "4000");
    entry(o, "EXPENSE", "SHOPPING", "1000");
    finance.budget(o, month(), "HOUSING", new BigDecimal("9000"));
    var s = finance.summary(o, month());
    assertThat(s.income()).isEqualTo("20000.00");
    assertThat(s.expenses()).isEqualTo("15000.00");
    assertThat(s.net()).isEqualTo("5000.00");
    assertThat(s.savingsRate()).isEqualTo("25.0");
    assertThat(s.entryCount()).isEqualTo(4);
    assertThat(s.insights())
        .anyMatch(i -> i.title().contains("over budget") && i.detail().contains("1000.00"));
    assertThat(s.insights()).anyMatch(i -> i.detail().contains("100.00 THB"));
  }

  @Test
  void trackerRecordsNeverMoveAccountFundsAndBankMovementsAreNotIncome() {
    var o = owner();
    var a = accounts.create(o);
    banking.deposit(o, a.id(), new BigDecimal("100"));
    banking.withdraw(o, a.id(), new BigDecimal("10"));
    assertThat(finance.summary(o, month()).income()).isEqualTo("0.00");
    assertThat(finance.summary(o, month()).expenses()).isEqualTo("0.00");
    entry(o, "EXPENSE", "FOOD", "50");
    assertThat(accounts.mine(o).balance()).isEqualByComparingTo("90");
  }

  @Test
  void crossUserEntriesAndGoalsAreIsolated() {
    var a = owner();
    var b = owner();
    var e = finance.add(a, "EXPENSE", "FOOD", BigDecimal.TEN, "Lunch", today());
    assertThat(finance.entries(b, month(), 0, 20).content()).isEmpty();
    assertThatThrownBy(() -> finance.delete(b, e.id()))
        .isInstanceOf(ApiException.class)
        .hasMessage("Tracker entry not found");
    var goal = finance.goal(a, "Laptop", new BigDecimal("1000"));
    assertThatThrownBy(() -> finance.contribute(b, goal.id(), BigDecimal.ONE))
        .isInstanceOf(ApiException.class)
        .hasMessage("Savings goal not found");
    assertThat(finance.goals(b)).isEmpty();
    finance.delete(a, e.id());
    assertThat(finance.summary(a, month()).entryCount()).isZero();
  }

  @Test
  void invalidCategoryFutureDatesPrecisionAndMonthAreRejected() {
    var o = owner();
    assertThatThrownBy(() -> finance.add(o, "EXPENSE", "SALARY", BigDecimal.ONE, "", today()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> finance.add(o, "EXPENSE", "FOOD", BigDecimal.ONE, "", today().plusDays(1)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> finance.add(o, "EXPENSE", "FOOD", new BigDecimal("0.001"), "", today()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> finance.summary(o, "not-a-month")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> finance.budget(o, month(), "SALARY", BigDecimal.TEN))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void missingIncomeDoesNotProduceFakeSavingsPercentage() {
    var o = owner();
    assertThat(finance.summary(o, month()).savingsRate()).isNull();
    entry(o, "EXPENSE", "FOOD", "25");
    var s = finance.summary(o, month());
    assertThat(s.savingsRate()).isNull();
    assertThat(s.insights()).anyMatch(i -> i.title().equals("Income is missing"));
    entry(o, "INCOME", "SALARY", "10");
    assertThat(finance.summary(o, month()).net()).isEqualTo("-15.00");
    assertThat(finance.summary(o, month()).insights())
        .anyMatch(i -> i.title().contains("exceeds income"));
  }

  @Test
  void goalsTrackAllocationsAndCannotBeOverfunded() {
    var o = owner();
    var goal = finance.goal(o, "Emergency fund", new BigDecimal("1000"));
    var updated = finance.contribute(o, goal.id(), new BigDecimal("250"));
    assertThat(updated.percent()).isEqualTo("25");
    assertThat(updated.remaining()).isEqualTo("750.00");
    assertThatThrownBy(() -> finance.contribute(o, goal.id(), new BigDecimal("751")))
        .isInstanceOf(ApiException.class);
    assertThat(finance.goals(o).getFirst().saved()).isEqualTo("250.00");
  }

  @Test
  void goalAllocationsRemainBoundedUnderConcurrency() throws Exception {
    var o = owner();
    var goal = finance.goal(o, "Laptop", new BigDecimal("1000"));
    var start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(8)) {
      for (int i = 0; i < 8; i++)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    finance.contribute(o, goal.id(), new BigDecimal("300"));
                    return true;
                  } catch (ApiException e) {
                    if (!e.getMessage().contains("remaining")) throw e;
                    return false;
                  }
                }));
      start.countDown();
      int success = 0;
      for (var result : results) if (result.get(30, TimeUnit.SECONDS)) success++;
      assertThat(success).isEqualTo(3);
    }
    assertThat(finance.goals(o).getFirst().saved()).isEqualTo("900.00");
  }

  @Test
  void monthPaginationAndBudgetUpdatesDoNotDuplicateRows() {
    var o = owner();
    for (int i = 0; i < 5; i++) entry(o, "EXPENSE", "FOOD", "1");
    var list = finance.entries(o, month(), 1, 2);
    assertThat(list.content()).hasSize(2);
    assertThat(list.totalElements()).isEqualTo(5);
    assertThat(list.totalPages()).isEqualTo(3);
    finance.budget(o, month(), "FOOD", new BigDecimal("20"));
    finance.budget(o, month(), "FOOD", new BigDecimal("30"));
    assertThat(
            finance.summary(o, month()).categories().stream()
                .filter(c -> c.category().equals("FOOD"))
                .findFirst()
                .orElseThrow()
                .budget())
        .isEqualTo("30.00");
  }
}

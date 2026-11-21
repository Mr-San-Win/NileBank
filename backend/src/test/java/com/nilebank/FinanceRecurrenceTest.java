package com.nilebank;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nilebank.account.*;
import com.nilebank.common.ApiException;
import com.nilebank.finance.*;
import com.nilebank.transaction.BankingService;
import com.nilebank.user.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;

class FinanceRecurrenceTest extends PostgresTest {
  static final LocalDate TODAY = LocalDate.of(2026, 3, 31);
  @Autowired FinanceRecurrenceService recurring;
  @Autowired FinanceCategoryService categories;
  @Autowired FinanceService finance;
  @Autowired UserRepository users;
  @Autowired AccountService accounts;
  @Autowired BankingService banking;
  @Autowired MockMvc mvc;

  @MockitoBean(name = "financeClock")
  Clock clock;

  @MockitoSpyBean JdbcTemplate jdbc;

  @BeforeEach
  void time() {
    when(clock.instant()).thenReturn(Instant.parse("2026-03-31T05:00:00Z"));
    when(clock.getZone()).thenReturn(ZoneId.of("Asia/Bangkok"));
  }

  UUID owner() {
    return users
        .saveAndFlush(
            new BankUser("Recurring Demo", UUID.randomUUID() + "@example.test", "not-for-auth"))
        .getId();
  }

  FinanceRecurrenceService.Rule rule(UUID owner, String frequency, LocalDate start, LocalDate end) {
    return recurring.create(
        owner, "EXPENSE", "FOOD", BigDecimal.TEN, "Scheduled food", frequency, start, end);
  }

  long count(UUID owner) {
    return jdbc.queryForObject(
        "select count(*) from finance_entries where owner_id=?", Long.class, owner);
  }

  @Test
  void monthlySchedulesPreserveOriginalDayAndRespectEndDate() {
    var owner = owner();
    var rule = rule(owner, "MONTHLY", LocalDate.of(2026, 1, 31), TODAY);
    assertThat(recurring.materialize(owner)).isEqualTo(3);
    assertThat(recurring.materialize(owner)).isZero();
    assertThat(
            jdbc.queryForList(
                "select occurred_on from finance_entries where owner_id=? order by occurred_on",
                LocalDate.class,
                owner))
        .containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), TODAY);
    assertThat(recurring.list(owner).getFirst().status()).isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from finance_occurrences where rule_id=?", Long.class, rule.id()))
        .isEqualTo(3);
  }

  @Test
  void dailyAndWeeklyCatchUpAreIdempotentAndDoNotMoveMoney() {
    var owner = owner();
    var account = accounts.create(owner);
    banking.deposit(owner, account.id(), new BigDecimal("100"));
    rule(owner, "DAILY", TODAY.minusDays(2), null);
    rule(owner, "WEEKLY", TODAY.minusWeeks(2), null);
    assertThat(recurring.materialize(owner)).isEqualTo(6);
    assertThat(recurring.materialize(owner)).isZero();
    assertThat(count(owner)).isEqualTo(6);
    assertThat(accounts.mine(owner).balance()).isEqualByComparingTo("100");
    assertThat(banking.history(owner, 0, 10).totalElements()).isEqualTo(1);
  }

  @Test
  void pauseResumeSkipsPausedDatesAndActiveResumeDoesNotLoseBacklog() {
    var owner = owner();
    var rule = rule(owner, "DAILY", TODAY.minusDays(10), null);
    recurring.state(owner, rule.id(), "PAUSE");
    assertThat(recurring.materialize(owner)).isZero();
    recurring.state(owner, rule.id(), "RESUME");
    assertThat(recurring.materialize(owner)).isEqualTo(1);
    var second = owner();
    var active = rule(second, "DAILY", TODAY.minusDays(2), null);
    recurring.state(second, active.id(), "RESUME");
    assertThat(recurring.materialize(second)).isEqualTo(3);
  }

  @Test
  void concurrentGenerationCreatesEachOccurrenceOnce() throws Exception {
    var owner = owner();
    var rule = rule(owner, "DAILY", TODAY.minusDays(9), null);
    var ready = new CountDownLatch(6);
    var start = new CountDownLatch(1);
    int created = 0;
    try (var pool = Executors.newFixedThreadPool(6)) {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 6; i++)
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return recurring.materialize(owner);
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (var result : results) created += result.get(30, TimeUnit.SECONDS);
    }
    assertThat(created).isEqualTo(10);
    assertThat(count(owner)).isEqualTo(10);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from finance_occurrences where rule_id=?", Long.class, rule.id()))
        .isEqualTo(10);
  }

  @Test
  void deletedGeneratedEntriesAreNotResurrectedByCursorReplay() {
    var owner = owner();
    var rule = rule(owner, "DAILY", TODAY, null);
    recurring.materialize(owner);
    UUID entry =
        jdbc.queryForObject("select id from finance_entries where owner_id=?", UUID.class, owner);
    finance.delete(owner, entry);
    jdbc.update(
        "update finance_recurring_rules set next_occurrence=? where id=?", TODAY, rule.id());
    assertThat(recurring.materialize(owner)).isZero();
    assertThat(count(owner)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from finance_occurrences where rule_id=?", Long.class, rule.id()))
        .isEqualTo(1);
  }

  @Test
  void occurrenceClaimAndCursorRollBackWhenEntryInsertFails() {
    var owner = owner();
    var rule = rule(owner, "DAILY", TODAY, null);
    doThrow(new org.springframework.dao.DataIntegrityViolationException("Synthetic entry failure"))
        .when(jdbc)
        .update(startsWith("insert into finance_entries"), any(Object[].class));
    assertThatThrownBy(() -> recurring.materialize(owner))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(count(owner)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from finance_occurrences where rule_id=?", Long.class, rule.id()))
        .isZero();
    assertThat(recurring.list(owner).getFirst().nextOccurrence()).isEqualTo(TODAY);
    reset(jdbc);
    assertThat(recurring.materialize(owner)).isEqualTo(1);
  }

  @Test
  void missedRunsAreBoundedAndEventuallyCatchUp() {
    var owner = owner();
    rule(owner, "DAILY", TODAY.minusDays(249), null);
    assertThat(recurring.materialize(owner)).isEqualTo(100);
    assertThat(recurring.materialize(owner)).isEqualTo(100);
    assertThat(recurring.materialize(owner)).isEqualTo(50);
    assertThat(recurring.materialize(owner)).isZero();
    assertThat(count(owner)).isEqualTo(250);
  }

  @Test
  void editsAffectFutureOccurrencesOnlyAndCancellationStopsGeneration() {
    var owner = owner();
    var rule = rule(owner, "DAILY", TODAY.minusDays(1), TODAY.minusDays(1));
    recurring.materialize(owner);
    recurring.edit(
        owner, rule.id(), "EXPENSE", "FOOD", new BigDecimal("20"), "Changed future", TODAY);
    recurring.state(owner, rule.id(), "RESUME");
    assertThat(recurring.materialize(owner)).isEqualTo(1);
    assertThat(
            jdbc.queryForList(
                "select amount from finance_entries where owner_id=? order by occurred_on",
                BigDecimal.class,
                owner))
        .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .containsExactly(new BigDecimal("10"), new BigDecimal("20"));
    recurring.state(owner, rule.id(), "CANCEL");
    recurring.state(owner, rule.id(), "CANCEL");
    assertThat(recurring.materialize(owner)).isZero();
    assertThatThrownBy(() -> recurring.state(owner, rule.id(), "RESUME"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void categoryArchivalPausesRulesUntilAnActiveCategoryIsChosen() {
    var owner = owner();
    var category = categories.create(owner, "EXPENSE", "Pets");
    var rule =
        recurring.create(
            owner, "EXPENSE", category.code(), BigDecimal.TEN, "Pets", "DAILY", TODAY, null);
    categories.archive(owner, category.code());
    assertThat(recurring.list(owner).getFirst().status()).isEqualTo("PAUSED");
    assertThat(recurring.materialize(owner)).isZero();
    assertThatThrownBy(() -> recurring.state(owner, rule.id(), "RESUME"))
        .isInstanceOf(ApiException.class);
    recurring.edit(owner, rule.id(), "EXPENSE", "FOOD", BigDecimal.ONE, "New category", null);
    recurring.state(owner, rule.id(), "RESUME");
    assertThat(recurring.materialize(owner)).isEqualTo(1);
  }

  @Test
  void ownershipFutureDatesAndInputValidationAreEnforced() {
    var owner = owner();
    var stranger = owner();
    var rule = rule(owner, "DAILY", TODAY.plusDays(1), null);
    assertThat(recurring.materialize(owner)).isZero();
    assertThat(recurring.list(stranger)).isEmpty();
    assertThatThrownBy(
            () -> recurring.edit(stranger, rule.id(), "EXPENSE", "FOOD", BigDecimal.ONE, "", null))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> recurring.state(stranger, rule.id(), "CANCEL"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> rule(owner, "YEARLY", TODAY, null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> rule(owner, "DAILY", TODAY, TODAY.minusDays(1)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                recurring.create(
                    owner, "EXPENSE", "SALARY", BigDecimal.ONE, "", "DAILY", TODAY, null))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void apiUsesCsrfAndKeepsGeneratedMoneyAsDecimalStrings() throws Exception {
    var owner = owner();
    rule(owner, "DAILY", TODAY, null);
    mvc.perform(post("/api/v1/finance/recurring/materialize").with(user(owner.toString())))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/finance/recurring/materialize").with(user(owner.toString())).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(1));
    mvc.perform(get("/api/v1/finance/recurring").with(user(owner.toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].amount").value("10.00"))
        .andExpect(jsonPath("$[0].owner").doesNotExist());
  }

  @Test
  void categoryArchivalAndGenerationDoNotDeadlockOrGenerateAfterArchival() throws Exception {
    for (int attempt = 0; attempt < 4; attempt++) {
      var owner = owner();
      var category = categories.create(owner, "EXPENSE", "Race category");
      recurring.create(
          owner, "EXPENSE", category.code(), BigDecimal.ONE, "Concurrent", "DAILY", TODAY, null);
      var start = new CountDownLatch(1);
      try (var pool = Executors.newFixedThreadPool(2)) {
        var generate =
            pool.submit(
                () -> {
                  start.await();
                  return recurring.materialize(owner);
                });
        var archive =
            pool.submit(
                () -> {
                  start.await();
                  categories.archive(owner, category.code());
                  return true;
                });
        start.countDown();
        generate.get(15, TimeUnit.SECONDS);
        assertThat(archive.get(15, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(recurring.list(owner).getFirst().status()).isEqualTo("PAUSED");
      assertThat(recurring.materialize(owner)).isZero();
      assertThat(count(owner)).isBetween(0L, 1L);
    }
  }
}

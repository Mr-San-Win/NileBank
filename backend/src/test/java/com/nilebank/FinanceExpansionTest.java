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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class FinanceExpansionTest extends PostgresTest {
  @Autowired FinanceCategoryService categories;
  @Autowired FinanceService finance;
  @Autowired UserRepository users;
  @Autowired AccountService accounts;
  @Autowired BankingService banking;
  @Autowired JdbcTemplate jdbc;

  UUID owner() {
    return users
        .saveAndFlush(
            new BankUser("Finance Demo", UUID.randomUUID() + "@example.test", "not-for-auth"))
        .getId();
  }

  LocalDate today() {
    return LocalDate.now(ZoneId.of("Asia/Bangkok"));
  }

  String month() {
    return YearMonth.from(today()).toString();
  }

  @Test
  void newUsersReceiveIndependentEditableDefaultCategories() {
    var a = owner();
    var b = owner();
    assertThat(categories.list(a)).hasSize(11);
    categories.rename(a, "FOOD", "Daily meals");
    assertThat(finance.categories(a).labels().get("FOOD")).isEqualTo("Daily meals");
    assertThat(finance.categories(b).labels().get("FOOD")).isEqualTo("Food");
  }

  @Test
  void customCategoriesSupportOwnedEntriesBudgetsAndInsights() {
    var a = owner();
    var b = owner();
    var category = categories.create(a, "EXPENSE", "Pets");
    finance.add(a, "EXPENSE", category.code(), new BigDecimal("120"), "Supplies", today());
    finance.budget(a, month(), category.code(), new BigDecimal("100"));
    var summary = finance.summary(a, month());
    assertThat(summary.expenses()).isEqualTo("120.00");
    var row =
        summary.categories().stream()
            .filter(c -> c.category().equals(category.code()))
            .findFirst()
            .orElseThrow();
    assertThat(row.remaining()).isEqualTo("-20.00");
    assertThat(row.percent()).isEqualTo("120");
    assertThat(summary.insights())
        .anyMatch(i -> i.title().contains("Pets") && i.title().contains("over budget"));
    assertThatThrownBy(
            () -> finance.add(b, "EXPENSE", category.code(), BigDecimal.ONE, "", today()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> categories.rename(b, category.code(), "Stolen"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void archiveKeepsHistoryAndPreventsNewAssignment() {
    var a = owner();
    var category = categories.create(a, "EXPENSE", "Pets");
    var entry = finance.add(a, "EXPENSE", category.code(), BigDecimal.TEN, "Before", today());
    categories.archive(a, category.code());
    assertThat(finance.categories(a).expense()).doesNotContain(category.code());
    assertThat(finance.summary(a, month()).expenses()).isEqualTo("10.00");
    assertThatThrownBy(
            () -> finance.add(a, "EXPENSE", category.code(), BigDecimal.ONE, "", today()))
        .isInstanceOf(ApiException.class);
    assertThat(
            finance
                .edit(
                    a,
                    entry.id(),
                    "EXPENSE",
                    category.code(),
                    new BigDecimal("12"),
                    "Historical correction",
                    today())
                .amount())
        .isEqualTo("12.00");
  }

  @Test
  void editingAndDeletingEntriesNeverMovesBankFunds() {
    var a = owner();
    var bank = accounts.create(a);
    banking.deposit(a, bank.id(), new BigDecimal("100"));
    var entry = finance.add(a, "INCOME", "SALARY", new BigDecimal("50"), "Income", today());
    finance.edit(a, entry.id(), "EXPENSE", "FOOD", new BigDecimal("25.50"), "Corrected", today());
    assertThat(finance.summary(a, month()).income()).isEqualTo("0.00");
    assertThat(finance.summary(a, month()).expenses()).isEqualTo("25.50");
    assertThatThrownBy(
            () -> finance.edit(owner(), entry.id(), "EXPENSE", "FOOD", BigDecimal.ONE, "", today()))
        .isInstanceOf(ApiException.class);
    finance.delete(a, entry.id());
    assertThat(finance.summary(a, month()).entryCount()).isZero();
    assertThat(accounts.mine(a).balance()).isEqualByComparingTo("100");
    assertThat(banking.history(a, 0, 10).totalElements()).isEqualTo(1);
  }

  @Test
  void filtersSortsAndPaginationUseSafeAllowlistedQueries() {
    var a = owner();
    finance.add(a, "INCOME", "SALARY", new BigDecimal("100"), "", today());
    finance.add(a, "EXPENSE", "FOOD", new BigDecimal("3"), "", today());
    finance.add(a, "EXPENSE", "FOOD", BigDecimal.ONE, "", today());
    finance.add(a, "EXPENSE", "OTHER", new BigDecimal("2"), "", today());
    var selected = finance.entries(a, month(), 0, 1, "EXPENSE", "FOOD", "AMOUNT_ASC");
    assertThat(selected.totalElements()).isEqualTo(2);
    assertThat(selected.totalPages()).isEqualTo(2);
    assertThat(selected.content().getFirst().amount()).isEqualTo("1.00");
    assertThat(
            finance.entries(a, month(), 0, 10, "EXPENSE", null, "AMOUNT_DESC").content().stream()
                .map(FinanceService.EntryView::amount))
        .containsExactly("3.00", "2.00", "1.00");
    assertThatThrownBy(
            () ->
                finance.entries(
                    a, month(), 0, 10, null, null, "amount; DROP TABLE finance_entries"))
        .isInstanceOf(ApiException.class);
    assertThat(finance.entries(a, month(), 0, 10, null, "' OR true --", "DATE_DESC").content())
        .isEmpty();
    assertThat(finance.summary(a, month()).entryCount()).isEqualTo(4);
  }

  @Test
  void databasePreventsCrossOwnerAndWrongDirectionCategoryReferences() {
    var a = owner();
    var b = owner();
    var custom = categories.create(a, "EXPENSE", "Pets");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into"
                        + " finance_entries(id,owner_id,direction,category,amount,occurred_on,created_at)"
                        + " values(?,?,'EXPENSE',?,1,?,current_timestamp)",
                    UUID.randomUUID(),
                    b,
                    custom.code(),
                    today()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into finance_budgets(owner_id,month_start,category,amount)"
                        + " values(?,?,?,1)",
                    a,
                    YearMonth.from(today()).atDay(1),
                    "SALARY"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void categoryNamesAreBoundedAndUniqueWithinActiveDirection() {
    var a = owner();
    categories.create(a, "EXPENSE", "Pets");
    assertThatThrownBy(() -> categories.create(a, "EXPENSE", "pets"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> categories.create(a, "EXPENSE", " ")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> categories.create(a, "EXPENSE", "x".repeat(61)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void migrationBackfillsExistingRecordsAndSeedsTheCorrectSchemaForNewUsers() {
    String schema = "fin_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var config =
        org.flywaydb.core.Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema);
    config.target("12").load().migrate();
    UUID old = UUID.randomUUID();
    jdbc.update(
        "insert into "
            + schema
            + ".app_users(id,full_name,email,password_hash,created_at) values(?,'Existing"
            + " Demo',?,'fixture',current_timestamp)",
        old,
        old + "@example.test");
    jdbc.update(
        "insert into "
            + schema
            + ".finance_entries(id,owner_id,direction,category,amount,occurred_on,created_at)"
            + " values(?,?,'EXPENSE','FOOD',12,?,current_timestamp)",
        UUID.randomUUID(),
        old,
        today());
    jdbc.update(
        "insert into "
            + schema
            + ".finance_budgets(owner_id,month_start,category,amount) values(?,?,'FOOD',20)",
        old,
        YearMonth.from(today()).atDay(1));
    config.target("13").load().migrate();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from " + schema + ".finance_categories where owner_id=?",
                Long.class,
                old))
        .isEqualTo(11);
    assertThat(
            jdbc.queryForObject(
                "select amount from " + schema + ".finance_entries where owner_id=?",
                BigDecimal.class,
                old))
        .isEqualByComparingTo("12");
    UUID next = UUID.randomUUID();
    jdbc.update(
        "insert into "
            + schema
            + ".app_users(id,full_name,email,password_hash,created_at) values(?,'New"
            + " Demo',?,'fixture',current_timestamp)",
        next,
        next + "@example.test");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from " + schema + ".finance_categories where owner_id=?",
                Long.class,
                next))
        .isEqualTo(11);
  }
}

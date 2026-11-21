package com.nilebank.finance;

import com.nilebank.common.ApiException;
import com.nilebank.transaction.Money;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FinanceRecurrenceService {
  private final JdbcTemplate jdbc;
  private final FinanceCategoryService categories;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public FinanceRecurrenceService(
      JdbcTemplate jdbc,
      FinanceCategoryService categories,
      Clock financeClock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.categories = categories;
    this.clock = financeClock;
    transactions = new TransactionTemplate(manager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public record Rule(
      UUID id,
      @com.fasterxml.jackson.annotation.JsonIgnore UUID owner,
      String direction,
      String category,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          BigDecimal amount,
      String description,
      RecurrencePolicy.Frequency frequency,
      LocalDate startDate,
      LocalDate endDate,
      LocalDate nextOccurrence,
      String status) {}

  private Rule rule(ResultSet r, int n) throws SQLException {
    return new Rule(
        r.getObject("id", UUID.class),
        r.getObject("owner_id", UUID.class),
        r.getString("direction"),
        r.getString("category"),
        r.getBigDecimal("amount"),
        r.getString("description"),
        RecurrencePolicy.Frequency.valueOf(r.getString("frequency")),
        r.getObject("start_date", LocalDate.class),
        r.getObject("end_date", LocalDate.class),
        r.getObject("next_occurrence", LocalDate.class),
        r.getString("status"));
  }

  private Rule owned(UUID owner, UUID id, boolean lock) {
    return jdbc
        .query(
            "select * from finance_recurring_rules where owner_id=? and id=?"
                + (lock ? " for update" : ""),
            this::rule,
            owner,
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Recurring rule not found"));
  }

  private static RecurrencePolicy.Frequency frequency(String value) {
    try {
      return RecurrencePolicy.Frequency.valueOf(value);
    } catch (IllegalArgumentException | NullPointerException bad) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Choose daily, weekly or monthly recurrence");
    }
  }

  private String description(String input) {
    String value = input == null ? "" : input.strip();
    if (value.length() > 120)
      throw new ApiException(HttpStatus.BAD_REQUEST, "Description is limited to 120 characters");
    return value;
  }

  private void dates(LocalDate start, LocalDate end) {
    if (start == null
        || start.getYear() < 2000
        || start.getYear() > 2100
        || end != null && (end.isBefore(start) || end.getYear() > 2100))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Use a valid schedule between 2000 and 2100");
  }

  @Transactional(readOnly = true)
  public List<Rule> list(UUID owner) {
    return jdbc.query(
        "select * from finance_recurring_rules where owner_id=? order by created_at desc,id",
        this::rule,
        owner);
  }

  @Transactional
  public Rule create(
      UUID owner,
      String direction,
      String category,
      BigDecimal amount,
      String description,
      String frequency,
      LocalDate start,
      LocalDate end) {
    amount = Money.validate(amount);
    description = description(description);
    dates(start, end);
    var repeat = frequency(frequency);
    categories.require(owner, category, direction, false);
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " finance_recurring_rules(id,owner_id,direction,category,amount,description,frequency,start_date,end_date,next_occurrence,status)"
            + " values(?,?,?,?,?,?,?,?,?,?,'ACTIVE')",
        id,
        owner,
        direction,
        category,
        amount,
        description,
        repeat.name(),
        start,
        end,
        start);
    return owned(owner, id, false);
  }

  @Transactional
  public Rule edit(
      UUID owner,
      UUID id,
      String direction,
      String category,
      BigDecimal amount,
      String description,
      LocalDate end) {
    amount = Money.validate(amount);
    description = description(description);
    categories.require(
        owner, category, direction, false); // Category then rule lock, matching archival order.
    var rule = owned(owner, id, true);
    dates(rule.startDate(), end);
    if (rule.status().equals("CANCELLED"))
      throw new ApiException(HttpStatus.CONFLICT, "Cancelled rules cannot be edited");
    jdbc.update(
        "update finance_recurring_rules set"
            + " direction=?,category=?,amount=?,description=?,end_date=? where id=?",
        direction,
        category,
        amount,
        description,
        end,
        id);
    return owned(owner, id, false);
  }

  @Transactional
  public Rule state(UUID owner, UUID id, String action) {
    if (!List.of("PAUSE", "RESUME", "CANCEL").contains(action == null ? "" : action))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Choose pause, resume or cancel");
    Rule before = owned(owner, id, false);
    if (action.equals("RESUME"))
      categories.require(owner, before.category(), before.direction(), false);
    var rule = owned(owner, id, true);
    if (!rule.category().equals(before.category()) || !rule.direction().equals(before.direction()))
      throw new ApiException(HttpStatus.CONFLICT, "Rule changed; refresh before retrying");
    if (rule.status().equals("CANCELLED") && action.equals("CANCEL")) return rule;
    if (rule.status().equals("CANCELLED"))
      throw new ApiException(HttpStatus.CONFLICT, "Cancelled rules cannot be resumed or changed");
    if (rule.status().equals("ACTIVE") && action.equals("RESUME")) return rule;
    if (rule.status().equals("PAUSED") && action.equals("PAUSE")) return rule;
    String status =
        switch (action) {
          case "PAUSE" -> "PAUSED";
          case "RESUME" -> "ACTIVE";
          default -> "CANCELLED";
        };
    LocalDate next =
        action.equals("RESUME")
            ? RecurrencePolicy.onOrAfter(rule.startDate(), LocalDate.now(clock), rule.frequency())
            : rule.nextOccurrence();
    if (status.equals("ACTIVE") && rule.endDate() != null && next.isAfter(rule.endDate()))
      throw new ApiException(HttpStatus.CONFLICT, "Schedule has ended; extend it before resuming");
    jdbc.update(
        "update finance_recurring_rules set status=?,next_occurrence=? where id=?",
        status,
        next,
        id);
    return owned(owner, id, false);
  }

  public int materialize(UUID owner) {
    Objects.requireNonNull(owner);
    return generateSelected(owner);
  }

  public int materializeAll() {
    return generateSelected(null);
  }

  private int generateSelected(UUID owner) {
    LocalDate today = LocalDate.now(clock);
    String filter = owner == null ? "" : " and owner_id=?";
    Object[] args = owner == null ? new Object[] {today} : new Object[] {today, owner};
    var ids =
        jdbc.query(
            "select id from finance_recurring_rules where status='ACTIVE' and next_occurrence<=?"
                + filter
                + " order by next_occurrence,id limit 10",
            (r, n) -> r.getObject(1, UUID.class),
            args);
    int created = 0;
    for (UUID id : ids) created += transactions.execute(status -> generateRule(id, owner, today));
    return created;
  }

  private int generateRule(UUID id, UUID owner, LocalDate today) {
    String ownership = owner == null ? "" : " and owner_id=?";
    Object[] args = owner == null ? new Object[] {id, today} : new Object[] {id, today, owner};
    var rules =
        jdbc.query(
            "select * from finance_recurring_rules where id=? and status='ACTIVE' and"
                + " next_occurrence<=?"
                + ownership
                + " for update skip locked",
            this::rule,
            args);
    if (rules.isEmpty()) return 0;
    var rule = rules.getFirst();
    var next = rule.nextOccurrence();
    int created = 0, iterations = 0;
    while (!next.isAfter(today)
        && (rule.endDate() == null || !next.isAfter(rule.endDate()))
        && iterations++ < 100) {
      int claimed =
          jdbc.update(
              "insert into finance_occurrences(rule_id,occurred_on) values(?,?) on conflict do"
                  + " nothing",
              id,
              next);
      if (claimed == 1) {
        jdbc.update(
            "insert into"
                + " finance_entries(id,owner_id,direction,category,amount,description,occurred_on,created_at,recurring_rule_id,scheduled_for)"
                + " values(?,?,?,?,?,?,?,current_timestamp,?,?)",
            UUID.randomUUID(),
            rule.owner(),
            rule.direction(),
            rule.category(),
            rule.amount(),
            rule.description(),
            next,
            id,
            next);
        created++;
      }
      next = RecurrencePolicy.next(rule.startDate(), next, rule.frequency());
    }
    String state = rule.endDate() != null && next.isAfter(rule.endDate()) ? "COMPLETED" : "ACTIVE";
    jdbc.update(
        "update finance_recurring_rules set next_occurrence=?,status=? where id=?",
        next,
        state,
        id);
    return created;
  }
}

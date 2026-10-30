package com.nilebank.finance;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinanceRepository {
  private final JdbcTemplate jdbc;

  public FinanceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Entry(
      UUID id,
      String direction,
      String category,
      BigDecimal amount,
      String description,
      LocalDate occurredOn) {}

  private Entry entry(ResultSet r, int n) throws SQLException {
    return new Entry(
        r.getObject("id", UUID.class),
        r.getString("direction"),
        r.getString("category"),
        r.getBigDecimal("amount"),
        r.getString("description"),
        r.getObject("occurred_on", LocalDate.class));
  }

  public Entry add(
      UUID owner,
      String direction,
      String category,
      BigDecimal amount,
      String description,
      LocalDate day) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " finance_entries(id,owner_id,direction,category,amount,description,occurred_on,created_at)"
            + " values(?,?,?,?,?,?,?,current_timestamp)",
        id,
        owner,
        direction,
        category,
        amount,
        description,
        java.sql.Date.valueOf(day));
    return new Entry(id, direction, category, amount, description, day);
  }

  public List<Entry> entries(UUID owner, YearMonth month, int page, int size) {
    return jdbc.query(
        "select * from finance_entries where owner_id=? and occurred_on>=? and occurred_on<? order"
            + " by occurred_on desc,created_at desc,id desc limit ? offset ?",
        this::entry,
        owner,
        java.sql.Date.valueOf(month.atDay(1)),
        java.sql.Date.valueOf(month.plusMonths(1).atDay(1)),
        size,
        (long) page * size);
  }

  public long count(UUID owner, YearMonth month) {
    return jdbc.queryForObject(
        "select count(*) from finance_entries where owner_id=? and occurred_on>=? and"
            + " occurred_on<?",
        Long.class,
        owner,
        java.sql.Date.valueOf(month.atDay(1)),
        java.sql.Date.valueOf(month.plusMonths(1).atDay(1)));
  }

  public boolean delete(UUID owner, UUID id) {
    return jdbc.update("delete from finance_entries where id=? and owner_id=?", id, owner) == 1;
  }

  public record Total(String direction, String category, BigDecimal amount, long count) {}

  public List<Total> totals(UUID owner, YearMonth month) {
    return jdbc.query(
        "select direction,category,sum(amount) amount,count(*) count from finance_entries where"
            + " owner_id=? and occurred_on>=? and occurred_on<? group by direction,category",
        (r, n) ->
            new Total(
                r.getString("direction"),
                r.getString("category"),
                r.getBigDecimal("amount"),
                r.getLong("count")),
        owner,
        java.sql.Date.valueOf(month.atDay(1)),
        java.sql.Date.valueOf(month.plusMonths(1).atDay(1)));
  }

  public record Budget(String category, BigDecimal amount) {}

  public List<Budget> budgets(UUID owner, YearMonth month) {
    return jdbc.query(
        "select category,amount from finance_budgets where owner_id=? and month_start=? order by"
            + " category",
        (r, n) -> new Budget(r.getString(1), r.getBigDecimal(2)),
        owner,
        java.sql.Date.valueOf(month.atDay(1)));
  }

  public void budget(UUID owner, YearMonth month, String category, BigDecimal amount) {
    jdbc.update(
        "insert into finance_budgets(owner_id,month_start,category,amount) values(?,?,?,?) on"
            + " conflict(owner_id,month_start,category) do update set amount=excluded.amount",
        owner,
        java.sql.Date.valueOf(month.atDay(1)),
        category,
        amount);
  }

  public record Goal(UUID id, String name, BigDecimal target, BigDecimal saved) {}

  private Goal goal(ResultSet r, int n) throws SQLException {
    return new Goal(
        r.getObject("id", UUID.class),
        r.getString("name"),
        r.getBigDecimal("target"),
        r.getBigDecimal("saved"));
  }

  public List<Goal> goals(UUID owner) {
    return jdbc.query(
        "select * from savings_goals where owner_id=? order by created_at,id", this::goal, owner);
  }

  public Goal createGoal(UUID owner, String name, BigDecimal target) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into savings_goals(id,owner_id,name,target,saved,created_at)"
            + " values(?,?,?,?,0,current_timestamp)",
        id,
        owner,
        name,
        target);
    return new Goal(id, name, target, new BigDecimal("0.00"));
  }

  public Optional<Goal> lockGoal(UUID owner, UUID id) {
    return jdbc
        .query(
            "select * from savings_goals where id=? and owner_id=? for update",
            this::goal,
            id,
            owner)
        .stream()
        .findFirst();
  }

  public void saved(UUID id, BigDecimal amount) {
    jdbc.update("update savings_goals set saved=? where id=?", amount, id);
  }
}

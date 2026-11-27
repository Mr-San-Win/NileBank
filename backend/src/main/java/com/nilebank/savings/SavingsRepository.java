package com.nilebank.savings;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SavingsRepository {
  private final JdbcTemplate jdbc;

  public SavingsRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Account(
      UUID id,
      UUID parent,
      String name,
      BigDecimal balance,
      String status,
      BigDecimal target,
      Instant createdAt) {}

  private Account account(ResultSet r, int n) throws SQLException {
    return new Account(
        r.getObject("id", UUID.class),
        r.getObject("account_id", UUID.class),
        r.getString("name"),
        r.getBigDecimal("balance"),
        r.getString("status"),
        r.getBigDecimal("target"),
        r.getTimestamp("created_at").toInstant());
  }

  public List<Account> list(UUID parent) {
    return jdbc.query(
        "select * from savings_accounts where account_id=? order by created_at,id",
        this::account,
        parent);
  }

  public Optional<Account> find(UUID parent, UUID id, boolean lock) {
    return jdbc
        .query(
            "select * from savings_accounts where account_id=? and id=?"
                + (lock ? " for update" : ""),
            this::account,
            parent,
            id)
        .stream()
        .findFirst();
  }

  public Account create(UUID parent, String name, BigDecimal target) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into savings_accounts(id,account_id,name,target) values(?,?,?,?)",
        id,
        parent,
        name,
        target);
    return find(parent, id, false).orElseThrow();
  }

  public void metadata(UUID id, String name, BigDecimal target, String status) {
    jdbc.update(
        "update savings_accounts set name=?,target=?,status=? where id=?",
        name,
        target,
        status,
        id);
  }

  public void balance(UUID id, BigDecimal balance) {
    jdbc.update("update savings_accounts set balance=? where id=?", balance, id);
  }

  public record Movement(
      UUID id,
      UUID savingsId,
      String direction,
      BigDecimal amount,
      String key,
      UUID transactionId,
      String reference,
      BigDecimal mainAfter,
      BigDecimal savingsAfter,
      Instant createdAt) {}

  private Movement movement(ResultSet r, int n) throws SQLException {
    return new Movement(
        r.getObject("id", UUID.class),
        r.getObject("savings_id", UUID.class),
        r.getString("direction"),
        r.getBigDecimal("amount"),
        r.getString("client_key"),
        r.getObject("transaction_id", UUID.class),
        r.getString("reference"),
        r.getBigDecimal("main_balance_after"),
        r.getBigDecimal("savings_balance_after"),
        r.getTimestamp("created_at").toInstant());
  }

  private static final String MOVEMENTS =
      "select m.*,t.reference from savings_movements m join banking_transactions t on"
          + " t.id=m.transaction_id";

  public Optional<Movement> prior(UUID parent, String key) {
    return jdbc
        .query(MOVEMENTS + " where m.account_id=? and m.client_key=?", this::movement, parent, key)
        .stream()
        .findFirst();
  }

  public void movement(
      UUID parent,
      UUID savings,
      String direction,
      BigDecimal amount,
      String key,
      UUID transaction,
      BigDecimal mainAfter,
      BigDecimal savingsAfter) {
    jdbc.update(
        "insert into"
            + " savings_movements(id,account_id,savings_id,direction,amount,client_key,transaction_id,main_balance_after,savings_balance_after)"
            + " values(?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        parent,
        savings,
        direction,
        amount,
        key,
        transaction,
        mainAfter,
        savingsAfter);
  }

  public List<Movement> history(UUID parent, UUID savings, int page, int size) {
    return jdbc.query(
        MOVEMENTS
            + " where m.account_id=? and m.savings_id=? order by m.created_at desc,m.id desc limit"
            + " ? offset ?",
        this::movement,
        parent,
        savings,
        size,
        (long) page * size);
  }

  public long count(UUID parent, UUID savings) {
    return jdbc.queryForObject(
        "select count(*) from savings_movements where account_id=? and savings_id=?",
        Long.class,
        parent,
        savings);
  }
}

package com.nilebank.partner;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PartnerRepository {
  private final JdbcTemplate jdbc;

  public PartnerRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Connection(
      UUID id, UUID account, String bankCode, String status, BigDecimal balance) {}

  public List<Connection> list(UUID account) {
    return jdbc.query(
        "select * from partner_connections where account_id=? order by bank_code",
        (r, n) ->
            new Connection(
                r.getObject("id", UUID.class),
                r.getObject("account_id", UUID.class),
                r.getString("bank_code"),
                r.getString("status"),
                r.getBigDecimal("balance")),
        account);
  }

  public Optional<Connection> find(UUID id, UUID account) {
    return list(account).stream().filter(c -> c.id().equals(id)).findFirst();
  }

  public Connection link(UUID account, String code) {
    jdbc.update(
        "insert into partner_connections(id,account_id,bank_code,status,balance,created_at)"
            + " values(?,?,?,'CONNECTED',5000,current_timestamp) on conflict(account_id,bank_code)"
            + " do update set status='CONNECTED'",
        UUID.randomUUID(),
        account,
        code);
    return list(account).stream().filter(c -> c.bankCode().equals(code)).findFirst().orElseThrow();
  }

  public void disconnect(UUID id) {
    jdbc.update("update partner_connections set status='DISCONNECTED' where id=?", id);
  }

  public void balance(UUID id, BigDecimal balance) {
    jdbc.update("update partner_connections set balance=? where id=?", balance, id);
  }

  public record Movement(
      UUID id,
      UUID connection,
      String direction,
      BigDecimal amount,
      String key,
      UUID transactionId,
      String reference,
      BigDecimal accountAfter,
      BigDecimal partnerAfter) {}

  public Optional<Movement> prior(UUID account, String key) {
    return jdbc
        .query(
            "select p.*,t.reference from partner_movements p join banking_transactions t on"
                + " t.id=p.transaction_id where p.account_id=? and p.client_key=?",
            (r, n) ->
                new Movement(
                    r.getObject("id", UUID.class),
                    r.getObject("connection_id", UUID.class),
                    r.getString("direction"),
                    r.getBigDecimal("amount"),
                    r.getString("client_key"),
                    r.getObject("transaction_id", UUID.class),
                    r.getString("reference"),
                    r.getBigDecimal("account_balance_after"),
                    r.getBigDecimal("partner_balance_after")),
            account,
            key)
        .stream()
        .findFirst();
  }

  public void save(UUID account, Movement m) {
    jdbc.update(
        "insert into"
            + " partner_movements(id,account_id,connection_id,direction,amount,client_key,transaction_id,account_balance_after,partner_balance_after,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,current_timestamp)",
        m.id(),
        account,
        m.connection(),
        m.direction(),
        m.amount(),
        m.key(),
        m.transactionId(),
        m.accountAfter(),
        m.partnerAfter());
  }
}

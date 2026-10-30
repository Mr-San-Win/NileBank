package com.nilebank.transaction;

import com.nilebank.common.ApiException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC participates in the same Spring-managed datasource transaction as JPA. */
@Repository
public class LedgerRepository {
  private final JdbcTemplate jdbc;
  private static final BigDecimal CAPACITY = new BigDecimal("99999999999999999.99");

  public LedgerRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record LockedAccount(UUID id, UUID owner, BigDecimal balance, String status) {
    public void requireActive() {
      if (!"ACTIVE".equals(status))
        throw new ApiException(HttpStatus.CONFLICT, "Account is frozen");
    }
  }

  private LockedAccount account(ResultSet r, int row) throws SQLException {
    return new LockedAccount(
        r.getObject("id", UUID.class),
        r.getObject("owner_id", UUID.class),
        r.getBigDecimal("balance"),
        r.getString("status"));
  }

  public LockedAccount lockOwned(UUID owner) {
    return jdbc
        .query(
            "select id,owner_id,balance,status from bank_accounts where owner_id=? for update",
            this::account,
            owner)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "Create your virtual account first"));
  }

  public UUID ownedId(UUID owner) {
    return jdbc
        .query(
            "select id from bank_accounts where owner_id=?",
            (r, n) -> r.getObject(1, UUID.class),
            owner)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "Create your virtual account first"));
  }

  public BigDecimal balance(UUID account, String currency) {
    List<BigDecimal> values =
        currency.equals("THB")
            ? jdbc.query(
                "select balance from bank_accounts where id=?",
                (r, n) -> r.getBigDecimal(1),
                account)
            : jdbc.query(
                "select balance from currency_wallets where account_id=? and currency=?",
                (r, n) -> r.getBigDecimal(1),
                account,
                currency);
    return values.isEmpty() ? new BigDecimal("0.00") : values.getFirst();
  }

  /** Call only while holding the parent account write lock, even for foreign wallets. */
  public void adjust(LockedAccount account, String currency, BigDecimal delta) {
    account.requireActive();
    BigDecimal next = balance(account.id(), currency).add(delta);
    if (next.signum() < 0)
      throw new ApiException(HttpStatus.CONFLICT, "Insufficient " + currency + " funds");
    if (next.compareTo(CAPACITY) > 0)
      throw new ApiException(HttpStatus.CONFLICT, "Account balance limit reached");
    if (currency.equals("THB"))
      jdbc.update(
          "update bank_accounts set balance=?,version=version+1 where id=?", next, account.id());
    else
      jdbc.update(
          "insert into currency_wallets(account_id,currency,balance) values(?,?,?) on"
              + " conflict(account_id,currency) do update set balance=excluded.balance",
          account.id(),
          currency,
          next);
  }

  public record Audit(UUID id, String reference) {}

  public Audit record(
      String type,
      UUID account,
      boolean incoming,
      BigDecimal amount,
      String currency,
      String counterCurrency,
      BigDecimal counterAmount,
      String description) {
    UUID id = UUID.randomUUID();
    String reference = "NB-" + UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " banking_transactions(id,type,status,amount,source_id,destination_id,reference,created_at,currency,counter_currency,counter_amount,description)"
            + " values(?,?,'SUCCESS',?,?,?,?,current_timestamp,?,?,?,?)",
        id,
        type,
        amount,
        incoming ? null : account,
        incoming ? account : null,
        reference,
        currency,
        counterCurrency,
        counterAmount,
        description);
    return new Audit(id, reference);
  }
}

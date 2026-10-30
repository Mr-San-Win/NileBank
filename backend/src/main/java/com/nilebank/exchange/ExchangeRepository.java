package com.nilebank.exchange;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExchangeRepository {
  private final JdbcTemplate jdbc;

  public ExchangeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Quote(
      UUID id,
      UUID owner,
      UUID account,
      String fromCurrency,
      String toCurrency,
      BigDecimal amount,
      BigDecimal receivedAmount,
      BigDecimal rate,
      Instant expiresAt,
      UUID transactionId,
      String reference,
      String provider,
      java.time.LocalDate rateDate,
      Instant fetchedAt) {}

  public void save(Quote q) {
    jdbc.update(
        "insert into"
            + " exchange_quotes(id,owner_id,account_id,from_currency,to_currency,amount,received_amount,rate,expires_at,created_at,provider,rate_date,fetched_at)"
            + " values(?,?,?,?,?,?,?,?,?,current_timestamp,?,?,?)",
        q.id(),
        q.owner(),
        q.account(),
        q.fromCurrency(),
        q.toCurrency(),
        q.amount(),
        q.receivedAmount(),
        q.rate(),
        Timestamp.from(q.expiresAt()),
        q.provider(),
        java.sql.Date.valueOf(q.rateDate()),
        Timestamp.from(q.fetchedAt()));
  }

  public Optional<Quote> find(UUID id, UUID owner) {
    return jdbc
        .query(
            "select q.*,t.reference from exchange_quotes q left join banking_transactions t on"
                + " t.id=q.executed_transaction_id where q.id=? and q.owner_id=?",
            (r, n) ->
                new Quote(
                    r.getObject("id", UUID.class),
                    r.getObject("owner_id", UUID.class),
                    r.getObject("account_id", UUID.class),
                    r.getString("from_currency"),
                    r.getString("to_currency"),
                    r.getBigDecimal("amount"),
                    r.getBigDecimal("received_amount"),
                    r.getBigDecimal("rate"),
                    r.getTimestamp("expires_at").toInstant(),
                    r.getObject("executed_transaction_id", UUID.class),
                    r.getString("reference"),
                    r.getString("provider"),
                    r.getObject("rate_date", java.time.LocalDate.class),
                    r.getTimestamp("fetched_at").toInstant()),
            id,
            owner)
        .stream()
        .findFirst();
  }

  public void executed(UUID quote, UUID transaction) {
    jdbc.update(
        "update exchange_quotes set executed_transaction_id=? where id=?", transaction, quote);
  }
}

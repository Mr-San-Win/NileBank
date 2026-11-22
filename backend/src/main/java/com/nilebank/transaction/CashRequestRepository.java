package com.nilebank.transaction;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CashRequestRepository {
  private final JdbcTemplate jdbc;

  public CashRequestRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Request(BigDecimal amount, UUID transactionId) {}

  public Optional<Request> prior(UUID account, BankingTransaction.Type operation, String key) {
    return jdbc
        .query(
            "select amount,transaction_id from cash_requests where account_id=? and operation=? and"
                + " client_key=?",
            (rs, n) ->
                new Request(rs.getBigDecimal("amount"), rs.getObject("transaction_id", UUID.class)),
            account,
            operation.name(),
            key)
        .stream()
        .findFirst();
  }

  public void record(
      UUID account,
      BankingTransaction.Type operation,
      String key,
      BigDecimal amount,
      UUID transaction) {
    jdbc.update(
        "insert into cash_requests(account_id,operation,client_key,amount,transaction_id)"
            + " values(?,?,?,?,?)",
        account,
        operation.name(),
        key,
        amount,
        transaction);
  }
}

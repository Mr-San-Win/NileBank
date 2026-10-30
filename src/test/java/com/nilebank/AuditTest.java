package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.nilebank.account.AccountService;
import com.nilebank.transaction.BankingService;
import com.nilebank.user.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditTest extends PostgresTest {
  @Autowired UserRepository users;
  @Autowired AccountService accounts;
  @Autowired BankingService banking;
  @Autowired JdbcTemplate jdbc;

  @Test
  void historyCannotBeUpdatedOrDeletedEvenThroughSql() {
    var user =
        users.saveAndFlush(
            new BankUser(
                "Audit Student",
                UUID.randomUUID() + "@example.test",
                "not-used-for-authentication"));
    var account = accounts.create(user.getId());
    var transaction = banking.deposit(user.getId(), account.id(), new BigDecimal("12.34"));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update banking_transactions set amount=1 where id=?", transaction.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update("delete from banking_transactions where id=?", transaction.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(banking.history(user.getId(), 0, 10).content().getFirst().amount())
        .isEqualByComparingTo("12.34");
  }
}

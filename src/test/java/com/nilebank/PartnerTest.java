package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.nilebank.account.*;
import com.nilebank.common.ApiException;
import com.nilebank.partner.*;
import com.nilebank.transaction.BankingService;
import com.nilebank.user.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PartnerTest extends PostgresTest {
  @Autowired PartnerService partners;
  @Autowired AccountService accounts;
  @Autowired UserRepository users;
  @Autowired BankingService banking;
  @Autowired JdbcTemplate jdbc;

  record Fixture(UUID owner, UUID account) {}

  Fixture fixture(String funds) {
    var user =
        users.saveAndFlush(
            new BankUser("Partner Student", UUID.randomUUID() + "@example.test", "not-for-auth"));
    var a = accounts.create(user.getId());
    if (new BigDecimal(funds).signum() > 0)
      banking.deposit(user.getId(), a.id(), new BigDecimal(funds));
    return new Fixture(user.getId(), a.id());
  }

  @Test
  void linkReconnectDoesNotMintAnotherOpeningBalance() {
    var f = fixture("0");
    var c = partners.link(f.owner(), "HARBOR", true);
    partners.transfer(f.owner(), c.id(), "IN", new BigDecimal("100"), "partner-key-1");
    partners.disconnect(f.owner(), c.id());
    var reconnect = partners.link(f.owner(), "HARBOR", true);
    assertThat(reconnect.id()).isEqualTo(c.id());
    assertThat(reconnect.balance()).isEqualTo("4900.00");
    assertThat(accounts.mine(f.owner()).balance()).isEqualByComparingTo("100");
  }

  @Test
  void transfersAreAtomicAndIdempotentWithPayloadConflicts() {
    var f = fixture("100");
    var c = partners.link(f.owner(), "HARBOR", true);
    var first = partners.transfer(f.owner(), c.id(), "OUT", new BigDecimal("25"), "partner-key-2");
    assertThat(
            partners
                .transfer(f.owner(), c.id(), "OUT", new BigDecimal("25.00"), "partner-key-2")
                .id())
        .isEqualTo(first.id());
    assertThatThrownBy(
            () ->
                partners.transfer(f.owner(), c.id(), "OUT", new BigDecimal("26"), "partner-key-2"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("different");
    assertThat(accounts.mine(f.owner()).balance()).isEqualByComparingTo("75");
    assertThat(partners.connections(f.owner()).getFirst().balance()).isEqualTo("5025.00");
    assertThat(banking.history(f.owner(), 0, 10).totalElements()).isEqualTo(2);
  }

  @Test
  void unsupportedBanksConsentOwnershipAndDisconnectedConnectionsAreRejected() {
    var f = fixture("100");
    var other = fixture("100");
    assertThatThrownBy(() -> partners.link(f.owner(), "REAL_BANK", true))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> partners.link(f.owner(), "HARBOR", false))
        .isInstanceOf(ApiException.class);
    var c = partners.link(f.owner(), "HARBOR", true);
    assertThatThrownBy(
            () -> partners.transfer(other.owner(), c.id(), "IN", BigDecimal.ONE, "ownership-key"))
        .isInstanceOf(ApiException.class)
        .hasMessage("Connection not found");
    partners.disconnect(f.owner(), c.id());
    assertThatThrownBy(
            () -> partners.transfer(f.owner(), c.id(), "IN", BigDecimal.ONE, "disconnected-key"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Reconnect");
  }

  @Test
  void failedCreditRollsBackExternalDebitAndKey() {
    var f = fixture("0");
    var c = partners.link(f.owner(), "HARBOR", true);
    jdbc.update("update bank_accounts set balance=99999999999999999.99 where id=?", f.account());
    assertThatThrownBy(
            () -> partners.transfer(f.owner(), c.id(), "IN", BigDecimal.ONE, "rollback-partner"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("limit");
    assertThat(partners.connections(f.owner()).getFirst().balance()).isEqualTo("5000.00");
    jdbc.update("update bank_accounts set balance=0 where id=?", f.account());
    partners.transfer(f.owner(), c.id(), "IN", BigDecimal.ONE, "rollback-partner");
    assertThat(accounts.mine(f.owner()).balance()).isEqualByComparingTo("1");
  }

  @Test
  void simultaneousPartnerTransfersCannotOverspend() throws Exception {
    var f = fixture("100");
    var c = partners.link(f.owner(), "HARBOR", true);
    var start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(8)) {
      for (int i = 0; i < 8; i++) {
        final int n = i;
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    partners.transfer(
                        f.owner(), c.id(), "OUT", new BigDecimal("80"), "parallel-partner-" + n);
                    return true;
                  } catch (ApiException e) {
                    if (!e.getMessage().startsWith("Insufficient")) throw e;
                    return false;
                  }
                }));
      }
      start.countDown();
      int success = 0;
      for (var result : results) if (result.get(30, TimeUnit.SECONDS)) success++;
      assertThat(success).isEqualTo(1);
    }
    assertThat(accounts.mine(f.owner()).balance()).isEqualByComparingTo("20");
    assertThat(partners.connections(f.owner()).getFirst().balance()).isEqualTo("5080.00");
  }
}

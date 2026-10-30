package com.nilebank.partner;

import com.nilebank.common.ApiException;
import com.nilebank.transaction.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartnerService {
  private static final Map<String, String> BANKS =
      Map.of("HARBOR", "Harbor Demo Bank", "ORCHID", "Orchid Demo Bank");
  private final LedgerRepository ledger;
  private final PartnerRepository partners;

  public PartnerService(LedgerRepository ledger, PartnerRepository partners) {
    this.ledger = ledger;
    this.partners = partners;
  }

  public record Bank(String code, String name, String description) {}

  public List<Bank> catalog() {
    return List.of(
        new Bank(
            "HARBOR",
            BANKS.get("HARBOR"),
            "Fictional demo bank · THB only · starts with 5,000 simulated THB"),
        new Bank(
            "ORCHID",
            BANKS.get("ORCHID"),
            "Fictional demo bank · THB only · starts with 5,000 simulated THB"));
  }

  public record ConnectionView(
      UUID id,
      String bankCode,
      String bankName,
      String status,
      String balance,
      String currency,
      String notice) {}

  private ConnectionView view(PartnerRepository.Connection c) {
    return new ConnectionView(
        c.id(),
        c.bankCode(),
        BANKS.get(c.bankCode()),
        c.status(),
        c.balance().toPlainString(),
        "THB",
        "Fictional connection. No real bank, credentials, or network integration.");
  }

  @Transactional(readOnly = true)
  public List<ConnectionView> connections(UUID owner) {
    return partners.list(ledger.ownedId(owner)).stream().map(this::view).toList();
  }

  @Transactional
  public ConnectionView link(UUID owner, String code, boolean consent) {
    if (!consent)
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Confirm that this is a fictional demo connection");
    if (code == null || !BANKS.containsKey(code))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported demo bank");
    var account = ledger.lockOwned(owner);
    account.requireActive();
    return view(partners.link(account.id(), code));
  }

  @Transactional
  public ConnectionView disconnect(UUID owner, UUID id) {
    var account = ledger.lockOwned(owner);
    var connection =
        partners
            .find(id, account.id())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Connection not found"));
    partners.disconnect(id);
    return view(partners.find(id, account.id()).orElseThrow());
  }

  public record MovementView(
      UUID id,
      UUID connectionId,
      String direction,
      String amount,
      String reference,
      UUID transactionId,
      String accountBalanceAfter,
      String partnerBalanceAfter) {}

  private MovementView view(PartnerRepository.Movement m) {
    return new MovementView(
        m.id(),
        m.connection(),
        m.direction(),
        m.amount().toPlainString(),
        m.reference(),
        m.transactionId(),
        m.accountAfter().toPlainString(),
        m.partnerAfter().toPlainString());
  }

  @Transactional
  public MovementView transfer(
      UUID owner, UUID connectionId, String direction, BigDecimal amount, String key) {
    amount = Money.validate(amount);
    if (!Set.of("IN", "OUT").contains(direction == null ? "" : direction))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Direction must be IN or OUT");
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}"))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Use an 8–100 character idempotency key");
    var account = ledger.lockOwned(owner);
    var prior = partners.prior(account.id(), key);
    if (prior.isPresent()) {
      var m = prior.get();
      if (!m.connection().equals(connectionId)
          || !m.direction().equals(direction)
          || m.amount().compareTo(amount) != 0)
        throw new ApiException(
            HttpStatus.CONFLICT,
            "Idempotency key was used with different partner-transfer parameters");
      return view(m);
    }
    account.requireActive();
    var connection =
        partners
            .find(connectionId, account.id())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Connection not found"));
    if (!connection.status().equals("CONNECTED"))
      throw new ApiException(HttpStatus.CONFLICT, "Reconnect this demo bank before moving funds");
    boolean incoming = direction.equals("IN");
    BigDecimal partnerAfter =
        incoming ? connection.balance().subtract(amount) : connection.balance().add(amount);
    if (partnerAfter.signum() < 0)
      throw new ApiException(HttpStatus.CONFLICT, "Insufficient demo partner funds");
    if (partnerAfter.compareTo(new BigDecimal("99999999999999999.99")) > 0)
      throw new ApiException(HttpStatus.CONFLICT, "Partner balance limit reached");
    // Both balances and the immutable audit share one database transaction and parent row lock.
    partners.balance(connectionId, partnerAfter);
    ledger.adjust(account, "THB", incoming ? amount : amount.negate());
    var audit =
        ledger.record(
            incoming ? "BANK_IN" : "BANK_OUT",
            account.id(),
            incoming,
            amount,
            "THB",
            null,
            null,
            BANKS.get(connection.bankCode())
                + " · simulated "
                + (incoming ? "top-up" : "transfer"));
    var movement =
        new PartnerRepository.Movement(
            UUID.randomUUID(),
            connectionId,
            direction,
            amount,
            key,
            audit.id(),
            audit.reference(),
            ledger.balance(account.id(), "THB"),
            partnerAfter);
    partners.save(account.id(), movement);
    return view(movement);
  }
}

package com.nilebank.savings;

import com.nilebank.common.ApiException;
import com.nilebank.transaction.*;
import java.math.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SavingsService {
  private static final BigDecimal CAPACITY = new BigDecimal("99999999999999999.99");
  private final LedgerRepository ledger;
  private final SavingsRepository savings;

  public SavingsService(LedgerRepository ledger, SavingsRepository savings) {
    this.ledger = ledger;
    this.savings = savings;
  }

  public record AccountView(
      UUID id,
      String name,
      String currency,
      String balance,
      String status,
      String target,
      String remaining,
      String percent,
      Instant createdAt) {}

  public record MovementView(
      UUID id,
      UUID savingsId,
      String direction,
      String currency,
      String amount,
      UUID transactionId,
      String reference,
      String mainBalanceAfter,
      String savingsBalanceAfter,
      Instant createdAt) {}

  public record History(
      List<MovementView> content, int page, int size, long totalElements, int totalPages) {}

  private AccountView view(SavingsRepository.Account a) {
    return new AccountView(
        a.id(),
        a.name(),
        "THB",
        a.balance().toPlainString(),
        a.status(),
        a.target() == null ? null : a.target().toPlainString(),
        a.target() == null
            ? null
            : a.target().subtract(a.balance()).max(BigDecimal.ZERO).setScale(2).toPlainString(),
        a.target() == null
            ? null
            : a.balance()
                .multiply(new BigDecimal("100"))
                .divide(a.target(), 0, RoundingMode.HALF_UP)
                .toPlainString(),
        a.createdAt());
  }

  private MovementView view(SavingsRepository.Movement m) {
    return new MovementView(
        m.id(),
        m.savingsId(),
        m.direction(),
        "THB",
        m.amount().toPlainString(),
        m.transactionId(),
        m.reference(),
        m.mainAfter().toPlainString(),
        m.savingsAfter().toPlainString(),
        m.createdAt());
  }

  private String name(String name) {
    if (name == null || name.isBlank() || name.strip().length() > 80)
      throw new ApiException(HttpStatus.BAD_REQUEST, "Savings name must contain 1-80 characters");
    return name.strip();
  }

  private SavingsRepository.Account owned(UUID parent, UUID id, boolean lock) {
    return savings
        .find(parent, id, lock)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Savings account not found"));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<AccountView> list(UUID owner) {
    return savings.list(ledger.ownedId(owner)).stream().map(this::view).toList();
  }

  @Transactional
  public AccountView create(UUID owner, String name, BigDecimal target) {
    name = name(name);
    if (target != null) target = Money.validate(target);
    var parent = ledger.lockOwned(owner);
    parent.requireActive();
    if (savings.list(parent.id()).size() >= 10)
      throw new ApiException(HttpStatus.CONFLICT, "The demo supports up to 10 savings accounts");
    return view(savings.create(parent.id(), name, target));
  }

  @Transactional
  public AccountView edit(UUID owner, UUID id, String name, BigDecimal target, String status) {
    name = name(name);
    if (target != null) target = Money.validate(target);
    if (!"ACTIVE".equals(status) && !"FROZEN".equals(status))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Choose active or frozen status");
    var parent = ledger.lockOwned(owner);
    owned(parent.id(), id, true);
    savings.metadata(id, name, target, status);
    return view(owned(parent.id(), id, false));
  }

  @Transactional
  public MovementView move(UUID owner, UUID id, String direction, BigDecimal amount, String key) {
    amount = Money.validate(amount);
    if (!"IN".equals(direction) && !"OUT".equals(direction))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a savings movement direction");
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}"))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Use an 8-100 character idempotency key");
    // Main account first, one owned savings row second: same order for every savings operation.
    var parent = ledger.lockOwned(owner);
    var account = owned(parent.id(), id, true);
    var prior = savings.prior(parent.id(), key);
    if (prior.isPresent()) {
      var original = prior.get();
      if (!original.savingsId().equals(id)
          || !original.direction().equals(direction)
          || original.amount().compareTo(amount) != 0)
        throw new ApiException(
            HttpStatus.CONFLICT, "Idempotency key was used with different savings parameters");
      return view(original);
    }
    parent.requireActive();
    if (!account.status().equals("ACTIVE"))
      throw new ApiException(HttpStatus.CONFLICT, "Savings account is frozen");
    boolean incoming = direction.equals("IN");
    BigDecimal next = account.balance().add(incoming ? amount : amount.negate());
    if (incoming) ledger.adjust(parent, "THB", amount.negate());
    if (next.signum() < 0)
      throw new ApiException(HttpStatus.CONFLICT, "Insufficient savings funds");
    if (next.compareTo(CAPACITY) > 0)
      throw new ApiException(HttpStatus.CONFLICT, "Savings balance limit reached");
    savings.balance(id, next);
    if (!incoming) ledger.adjust(parent, "THB", amount);
    var audit =
        ledger.record(
            incoming ? "SAVINGS_IN" : "SAVINGS_OUT",
            parent.id(),
            !incoming,
            amount,
            "THB",
            null,
            null,
            (incoming ? "To savings: " : "From savings: ") + account.name());
    savings.movement(
        parent.id(),
        id,
        direction,
        amount,
        key,
        audit.id(),
        ledger.balance(parent.id(), "THB"),
        next);
    return view(savings.prior(parent.id(), key).orElseThrow());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public History history(UUID owner, UUID id, int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Page must be nonnegative and size between 1 and 100");
    UUID parent = ledger.ownedId(owner);
    owned(parent, id, false);
    long total = savings.count(parent, id);
    return new History(
        savings.history(parent, id, page, size).stream().map(this::view).toList(),
        page,
        size,
        total,
        (int) ((total + size - 1) / size));
  }
}

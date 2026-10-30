package com.nilebank.transfer;

import com.nilebank.account.*;
import com.nilebank.common.ApiException;
import com.nilebank.transaction.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {
  private final AccountRepository accounts;
  private final TransactionRepository transactions;

  public TransferService(AccountRepository accounts, TransactionRepository transactions) {
    this.accounts = accounts;
    this.transactions = transactions;
  }

  @Transactional
  public TransactionView transfer(
      UUID owner, UUID sourceId, String recipientNumber, BigDecimal amount, String key) {
    amount = Money.validate(amount);
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}"))
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "Idempotency key must be 8–100 letters, digits, underscores or hyphens");
    // Ownership is checked before acquiring locks on a stranger's account.
    accounts
        .findOwnedId(sourceId, owner)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
    UUID destinationId =
        accounts
            .findIdByAccountNumber(recipientNumber)
            .orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "Recipient account not found"));
    if (sourceId.equals(destinationId))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a different recipient account");
    // Pre-lock lookups return scalar IDs, so no stale entity snapshot enters the persistence
    // context.
    List<UUID> ordered = new ArrayList<>(List.of(sourceId, destinationId));
    ordered.sort(Comparator.comparing(UUID::toString));
    BankAccount first = accounts.lockById(ordered.get(0)).orElseThrow();
    BankAccount second = accounts.lockById(ordered.get(1)).orElseThrow();
    BankAccount source = first.getId().equals(sourceId) ? first : second;
    BankAccount destination = first.getId().equals(destinationId) ? first : second;
    var prior = transactions.findBySourceIdAndIdempotencyKey(sourceId, key);
    if (prior.isPresent()) {
      var original = prior.get();
      if (!original.getDestination().getId().equals(destinationId)
          || original.getAmount().compareTo(amount) != 0)
        throw new ApiException(
            HttpStatus.CONFLICT, "Idempotency key was used with different transfer parameters");
      return TransactionView.of(original, sourceId);
    }
    source.requireActive();
    destination.requireActive();
    source.debit(amount);
    destination.credit(amount);
    var transfer =
        new BankingTransaction(BankingTransaction.Type.TRANSFER, amount, source, destination);
    transfer.setIdempotencyKey(key);
    return TransactionView.of(transactions.saveAndFlush(transfer), sourceId);
  }
}

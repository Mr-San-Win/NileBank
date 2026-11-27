package com.nilebank.transaction;

import com.nilebank.account.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankingService {
  private final AccountService accounts;
  private final TransactionRepository transactions;
  private final CashRequestRepository requests;
  private final jakarta.persistence.EntityManager entities;

  public BankingService(
      AccountService accounts,
      TransactionRepository transactions,
      CashRequestRepository requests,
      jakarta.persistence.EntityManager entities) {
    this.accounts = accounts;
    this.transactions = transactions;
    this.requests = requests;
    this.entities = entities;
  }

  @Transactional
  public TransactionView deposit(UUID owner, UUID accountId, BigDecimal amount) {
    return cash(owner, accountId, amount, null, BankingTransaction.Type.DEPOSIT);
  }

  @Transactional
  public TransactionView deposit(UUID owner, UUID accountId, BigDecimal amount, String key) {
    return cash(owner, accountId, amount, key, BankingTransaction.Type.DEPOSIT);
  }

  @Transactional
  public TransactionView withdraw(UUID owner, UUID accountId, BigDecimal amount) {
    return cash(owner, accountId, amount, null, BankingTransaction.Type.WITHDRAWAL);
  }

  @Transactional
  public TransactionView withdraw(UUID owner, UUID accountId, BigDecimal amount, String key) {
    return cash(owner, accountId, amount, key, BankingTransaction.Type.WITHDRAWAL);
  }

  private TransactionView cash(
      UUID owner, UUID accountId, BigDecimal amount, String key, BankingTransaction.Type type) {
    amount = Money.validate(amount);
    if (key != null && (key.length() < 8 || key.length() > 100 || key.isBlank()))
      throw new com.nilebank.common.ApiException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "Idempotency key must contain 8 to 100 characters");
    BankAccount account = accounts.lockOwned(owner, accountId);
    if (key != null) {
      var prior = requests.prior(accountId, type, key);
      if (prior.isPresent()) {
        if (prior.get().amount().compareTo(amount) != 0)
          throw new com.nilebank.common.ApiException(
              org.springframework.http.HttpStatus.CONFLICT,
              "Idempotency key was used with different parameters");
        return TransactionView.of(
            transactions.findById(prior.get().transactionId()).orElseThrow(), accountId);
      }
    }
    boolean deposit = type == BankingTransaction.Type.DEPOSIT;
    if (deposit) account.credit(amount);
    else account.debit(amount);
    var transaction =
        transactions.saveAndFlush(
            new BankingTransaction(
                type, amount, deposit ? null : account, deposit ? account : null));
    // Return database precision on the first response as well as on replay.
    entities.refresh(transaction);
    if (key != null) requests.record(accountId, type, key, amount, transaction.getId());
    return TransactionView.of(transaction, accountId);
  }

  public record History(
      List<TransactionView> content, int page, int size, long totalElements, int totalPages) {}

  @Transactional(readOnly = true)
  public History history(UUID owner, int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new com.nilebank.common.ApiException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "Page must be nonnegative and size between 1 and 100");
    UUID id = accounts.owned(owner).getId();
    var result =
        transactions.history(
            id,
            PageRequest.of(
                page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    return new History(
        result.getContent().stream().map(t -> TransactionView.of(t, id)).toList(),
        page,
        size,
        result.getTotalElements(),
        result.getTotalPages());
  }
}

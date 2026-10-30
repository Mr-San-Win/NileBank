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

  public BankingService(AccountService accounts, TransactionRepository transactions) {
    this.accounts = accounts;
    this.transactions = transactions;
  }

  @Transactional
  public TransactionView deposit(UUID owner, UUID accountId, BigDecimal amount) {
    amount = Money.validate(amount);
    BankAccount account = accounts.lockOwned(owner, accountId);
    account.credit(amount);
    return TransactionView.of(
        transactions.save(
            new BankingTransaction(BankingTransaction.Type.DEPOSIT, amount, null, account)),
        accountId);
  }

  @Transactional
  public TransactionView withdraw(UUID owner, UUID accountId, BigDecimal amount) {
    amount = Money.validate(amount);
    BankAccount account = accounts.lockOwned(owner, accountId);
    account.debit(amount);
    return TransactionView.of(
        transactions.save(
            new BankingTransaction(BankingTransaction.Type.WITHDRAWAL, amount, account, null)),
        accountId);
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

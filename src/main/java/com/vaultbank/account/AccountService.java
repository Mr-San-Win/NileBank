package com.vaultbank.account;

import com.vaultbank.common.ApiException;
import com.vaultbank.user.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  private final AccountRepository accounts;
  private final UserRepository users;

  public AccountService(AccountRepository accounts, UserRepository users) {
    this.accounts = accounts;
    this.users = users;
  }

  public record AccountView(
      UUID id,
      String accountNumber,
      String currency,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          BigDecimal balance,
      BankAccount.Status status,
      Instant createdAt) {}

  public static AccountView view(BankAccount a) {
    return new AccountView(
        a.getId(),
        a.getAccountNumber(),
        a.getCurrency(),
        a.getBalance(),
        a.getStatus(),
        a.getCreatedAt());
  }

  @Transactional
  public AccountView create(UUID owner) {
    users
        .lockById(owner)
        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Please log in"));
    if (accounts.findByOwnerId(owner).isPresent())
      throw new ApiException(HttpStatus.CONFLICT, "You already have an account");
    return view(accounts.saveAndFlush(new BankAccount(owner)));
  }

  @Transactional(readOnly = true)
  public AccountView mine(UUID owner) {
    return view(owned(owner));
  }

  public BankAccount owned(UUID owner) {
    return accounts
        .findByOwnerId(owner)
        .orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "Create your virtual account first"));
  }

  public BankAccount lockOwned(UUID owner, UUID accountId) {
    BankAccount a =
        accounts
            .lockById(accountId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
    if (!a.getOwnerId().equals(owner))
      throw new ApiException(HttpStatus.NOT_FOUND, "Account not found");
    return a;
  }
}

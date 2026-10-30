package com.vaultbank.account;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<BankAccount, UUID> {
  @Query("select a.id from BankAccount a where a.id=:id and a.ownerId=:owner")
  Optional<UUID> findOwnedId(@Param("id") UUID id, @Param("owner") UUID owner);

  @Query("select a.id from BankAccount a where a.accountNumber=:number")
  Optional<UUID> findIdByAccountNumber(@Param("number") String number);

  Optional<BankAccount> findByOwnerId(UUID ownerId);

  Optional<BankAccount> findByAccountNumber(String accountNumber);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from BankAccount a where a.id=:id")
  Optional<BankAccount> lockById(@Param("id") UUID id);
}

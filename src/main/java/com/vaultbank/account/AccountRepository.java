package com.vaultbank.account;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface AccountRepository extends JpaRepository<BankAccount,UUID> {
    Optional<BankAccount> findByOwnerId(UUID ownerId);
    Optional<BankAccount> findByAccountNumber(String accountNumber);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BankAccount a where a.id=:id")
    Optional<BankAccount> lockById(@Param("id") UUID id);
}

package com.vaultbank.user;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface UserRepository extends JpaRepository<BankUser,UUID> {
    Optional<BankUser> findByEmail(String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from BankUser u where u.id=:id")
    Optional<BankUser> lockById(@Param("id") UUID id);
}

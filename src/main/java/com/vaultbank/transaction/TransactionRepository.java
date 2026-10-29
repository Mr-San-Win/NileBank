package com.vaultbank.transaction;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface TransactionRepository extends JpaRepository<BankingTransaction,UUID> {
    @EntityGraph(attributePaths={"source","destination"})
    @Query("select t from BankingTransaction t where t.source.id=:id or t.destination.id=:id")
    Page<BankingTransaction> history(@Param("id") UUID id,Pageable pageable);
}

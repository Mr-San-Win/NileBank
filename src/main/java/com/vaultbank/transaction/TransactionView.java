package com.vaultbank.transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record TransactionView(UUID id,BankingTransaction.Type type,String status,BigDecimal amount,String direction,String sourceAccount,String destinationAccount,String reference,Instant createdAt){
    public static TransactionView of(BankingTransaction t,UUID accountId){
        return new TransactionView(t.getId(),t.getType(),t.getStatus(),t.getAmount(),
            t.getDestination()!=null&&t.getDestination().getId().equals(accountId)?"IN":"OUT",
            t.getSource()==null?null:t.getSource().getAccountNumber(),
            t.getDestination()==null?null:t.getDestination().getAccountNumber(),t.getReference(),t.getCreatedAt());
    }
}

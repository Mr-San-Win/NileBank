package com.vaultbank.account;
import com.vaultbank.common.ApiException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
@Entity
@Table(name="bank_accounts")
public class BankAccount {
    public enum Status { ACTIVE,FROZEN }
    @Id private UUID id;
    @Column(nullable=false,unique=true) private UUID ownerId;
    @Column(nullable=false,unique=true,length=32) private String accountNumber;
    @Column(nullable=false,length=3) private String currency;
    @Column(nullable=false,precision=19,scale=2) private BigDecimal balance;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=10) private Status status;
    @Version private long version;
    @Column(nullable=false) private Instant createdAt;
    protected BankAccount(){}
    public BankAccount(UUID ownerId){
        id=UUID.randomUUID();this.ownerId=ownerId;
        accountNumber="VB"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(java.util.Locale.ROOT);
        currency="THB";balance=new BigDecimal("0.00");status=Status.ACTIVE;createdAt=Instant.now();
    }
    public UUID getId(){return id;}
    public UUID getOwnerId(){return ownerId;}
    public String getAccountNumber(){return accountNumber;}
    public String getCurrency(){return currency;}
    public BigDecimal getBalance(){return balance;}
    public Status getStatus(){return status;}
    public Instant getCreatedAt(){return createdAt;}
    public void requireActive(){if(status!=Status.ACTIVE)throw new ApiException(HttpStatus.CONFLICT,"Account is frozen");}
    public void credit(BigDecimal amount){
        requireActive();
        BigDecimal next=balance.add(amount);
        if(next.compareTo(new BigDecimal("99999999999999999.99"))>0)throw new ApiException(HttpStatus.CONFLICT,"Account balance limit reached");
        balance=next;
    }
    public void debit(BigDecimal amount){
        requireActive();
        if(balance.compareTo(amount)<0)throw new ApiException(HttpStatus.CONFLICT,"Insufficient funds");
        balance=balance.subtract(amount);
    }
}

package com.nilebank.transaction;

import com.nilebank.account.BankAccount;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "banking_transactions")
public class BankingTransaction {
  public enum Type {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    EXCHANGE,
    BANK_IN,
    BANK_OUT
  }

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private Type type;

  @Column(nullable = false, length = 12)
  private String status;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal amount;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_id")
  private BankAccount source;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "destination_id")
  private BankAccount destination;

  @Column(nullable = false, unique = true, length = 40)
  private String reference;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(length = 100)
  private String idempotencyKey;
  @Column(nullable=false,length=3) private String currency="THB";
  @Column(length=3) private String counterCurrency;
  @Column(precision=19,scale=2) private BigDecimal counterAmount;
  @Column(length=180) private String description;
  public String getCurrency(){return currency;}
  public String getCounterCurrency(){return counterCurrency;}
  public BigDecimal getCounterAmount(){return counterAmount;}
  public String getDescription(){return description;}

  public void setIdempotencyKey(String key) {
    this.idempotencyKey = key;
  }

  protected BankingTransaction() {}

  public BankingTransaction(
      Type type, BigDecimal amount, BankAccount source, BankAccount destination) {
    id = UUID.randomUUID();
    this.type = type;
    this.amount = amount;
    this.source = source;
    this.destination = destination;
    reference = "NB-" + UUID.randomUUID();
    status = "SUCCESS";
    createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public Type getType() {
    return type;
  }

  public String getStatus() {
    return status;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public BankAccount getSource() {
    return source;
  }

  public BankAccount getDestination() {
    return destination;
  }

  public String getReference() {
    return reference;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

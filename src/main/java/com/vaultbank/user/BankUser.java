package com.vaultbank.user;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name="app_users")
public class BankUser {
    @Id private UUID id;
    @Column(nullable=false,length=100) private String fullName;
    @Column(nullable=false,unique=true,length=254) private String email;
    @Column(nullable=false,length=100) private String passwordHash;
    @Column(nullable=false) private Instant createdAt;
    protected BankUser() {}
    public BankUser(String fullName, String email, String passwordHash) {
        this.id=UUID.randomUUID(); this.fullName=fullName; this.email=email;
        this.passwordHash=passwordHash; this.createdAt=Instant.now();
    }
    public UUID getId(){return id;}
    public String getFullName(){return fullName;}
    public String getEmail(){return email;}
    public String getPasswordHash(){return passwordHash;}
    public Instant getCreatedAt(){return createdAt;}
}

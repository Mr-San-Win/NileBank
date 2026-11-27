package com.nilebank.auth;

import com.nilebank.user.BankUser;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "auth_identities",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_auth_identity_subject",
          columnNames = {"issuer", "provider_subject"}),
      @UniqueConstraint(
          name = "uq_auth_identity_user_provider",
          columnNames = {"user_id", "provider"})
    })
public class ExternalIdentity {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private BankUser user;

  @Column(nullable = false, length = 32)
  private String provider;

  @Column(nullable = false, length = 512)
  private String issuer;

  @Column(name = "provider_subject", nullable = false, length = 255)
  private String providerSubject;

  @Column(nullable = false)
  private Instant createdAt;

  protected ExternalIdentity() {}

  public ExternalIdentity(BankUser user, ValidatedExternalIdentity identity) {
    this.id = UUID.randomUUID();
    this.user = user;
    this.provider = identity.provider();
    this.issuer = identity.issuer();
    this.providerSubject = identity.subject();
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public BankUser getUser() {
    return user;
  }

  public String getProvider() {
    return provider;
  }

  public String getIssuer() {
    return issuer;
  }

  public String getProviderSubject() {
    return providerSubject;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

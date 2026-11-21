package com.nilebank.auth;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, UUID> {
  Optional<ExternalIdentity> findByUser_IdAndProvider(UUID userId, String provider);

  Optional<ExternalIdentity> findByIssuerAndProviderSubject(String issuer, String providerSubject);
}

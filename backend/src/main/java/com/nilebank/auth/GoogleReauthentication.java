package com.nilebank.auth;

import com.nilebank.common.ApiException;
import com.nilebank.user.*;
import java.time.Instant;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal, validated same-identity recent-authentication proof; no email-based resolution. */
@Service
public class GoogleReauthentication {
  public static final String SESSION_INTENT = "nilebank.googleReauthenticationIntent";
  public static final String REQUEST_INTENT = "nilebank.googleReauthenticationRequest";

  public record Intent(
      UUID owner, long version, String issuer, String subject, Instant started, Instant expires)
      implements java.io.Serializable {}

  public record Proof(
      UUID owner, long version, String issuer, String subject, Instant at, Instant expires)
      implements java.io.Serializable {}

  private final boolean enabled;
  private final UserRepository users;
  private final ExternalIdentityRepository identities;

  public GoogleReauthentication(
      Environment env, UserRepository users, ExternalIdentityRepository identities) {
    this.users = users;
    this.identities = identities;
    enabled = env.getProperty("nilebank.auth.google.reauth-enabled", Boolean.class, false);
    if (enabled && !env.getProperty("nilebank.auth.google.enabled", Boolean.class, false))
      throw new IllegalStateException(
          "Google reauthentication requires Google sign-in configuration");
  }

  public boolean supports(UUID owner) {
    return enabled && identities.findByUser_IdAndProvider(owner, "google").isPresent();
  }

  @Transactional
  public Intent prepare(UUID owner) {
    var user = users.lockById(owner).orElseThrow(GoogleReauthentication::invalid);
    if (!enabled)
      throw new ApiException(
          HttpStatus.CONFLICT, "Google verification is unavailable in this environment");
    var identity =
        identities
            .findByUser_IdAndProvider(owner, "google")
            .orElseThrow(GoogleReauthentication::invalid);
    Instant now = Instant.now();
    return new Intent(
        owner,
        user.getCredentialVersion(),
        identity.getIssuer(),
        identity.getProviderSubject(),
        now,
        now.plusSeconds(300));
  }

  public Proof validate(
      Intent intent,
      ValidatedExternalIdentity identity,
      Instant authenticatedAt,
      Authentication current) {
    Instant now = Instant.now();
    if (!enabled
        || intent == null
        || current == null
        || current.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_USER"))
        || !current.getName().equals(intent.owner.toString())
        || !intent.expires.isAfter(now)
        || !identity.issuer().equals(intent.issuer)
        || !identity.subject().equals(intent.subject)
        || authenticatedAt == null
        || authenticatedAt.isBefore(intent.started.minusSeconds(30))
        || authenticatedAt.isBefore(now.minusSeconds(300))
        || authenticatedAt.isAfter(now.plusSeconds(30))) throw invalid();
    Instant expires =
        authenticatedAt.plusSeconds(300).isBefore(now.plusSeconds(300))
            ? authenticatedAt.plusSeconds(300)
            : now.plusSeconds(300);
    return new Proof(intent.owner, intent.version, intent.issuer, intent.subject, now, expires);
  }

  public boolean valid(BankUser user, Proof proof) {
    Instant now = Instant.now();
    if (!enabled
        || proof == null
        || proof.owner == null
        || proof.issuer == null
        || proof.subject == null
        || proof.at == null
        || proof.expires == null
        || !proof.owner.equals(user.getId())
        || proof.version != user.getCredentialVersion()
        || !proof.expires.isAfter(now)
        || proof.at.isAfter(now)) return false;
    return identities
        .findByIssuerAndProviderSubject(proof.issuer, proof.subject)
        .map(i -> i.getUser().getId().equals(user.getId()))
        .orElse(false);
  }

  public static Proof proof(Authentication authentication) {
    return authentication != null
            && authentication.getPrincipal() instanceof ReauthenticatedOidcUser user
        ? user.proof()
        : null;
  }

  static ApiException invalid() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED,
        "Complete recent Google verification before changing authenticator protection");
  }
}

package com.nilebank.auth;

import com.nilebank.common.ApiException;
import com.nilebank.user.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountSecurityService {
  private final UserRepository users;
  private final ExternalIdentityRepository identities;
  private final PasswordEncoder encoder;

  public AccountSecurityService(
      UserRepository users, ExternalIdentityRepository identities, PasswordEncoder encoder) {
    this.users = users;
    this.identities = identities;
    this.encoder = encoder;
  }

  public record Methods(
      boolean password, boolean google, boolean recoveryAvailable, boolean mfaAvailable) {}

  public record Identity(UUID id, long version) {}

  public record LinkIntent(UUID owner, long version, Instant expiresAt)
      implements java.io.Serializable {}

  @Transactional(readOnly = true)
  public Methods methods(UUID owner) {
    var user = users.findById(owner).orElseThrow(AccountSecurityService::unauthorized);
    return new Methods(
        user.getPasswordHash() != null,
        identities.findByUser_IdAndProvider(owner, "google").isPresent(),
        false,
        false);
  }

  private BankUser lock(UUID owner) {
    return users.lockById(owner).orElseThrow(AccountSecurityService::unauthorized);
  }

  private void proof(BankUser user, String password) {
    if (user.getPasswordHash() == null
        || password == null
        || password.getBytes(StandardCharsets.UTF_8).length > 72
        || !encoder.matches(password, user.getPasswordHash())) throw unauthorized();
  }

  private static ApiException unauthorized() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
  }

  @Transactional
  public LinkIntent prepareLink(UUID owner, String password) {
    var user = lock(owner);
    proof(user, password);
    if (identities.findByUser_IdAndProvider(owner, "google").isPresent())
      throw new ApiException(HttpStatus.CONFLICT, "Google is already linked");
    return new LinkIntent(owner, user.getCredentialVersion(), Instant.now().plusSeconds(300));
  }

  @Transactional
  public Identity link(LinkIntent intent, ValidatedExternalIdentity identity) {
    if (intent == null) throw unauthorized();
    var user = lock(intent.owner());
    if (!intent.expiresAt().isAfter(Instant.now())
        || user.getCredentialVersion() != intent.version()
        || user.getPasswordHash() == null) throw unauthorized();
    var prior = identities.findByIssuerAndProviderSubject(identity.issuer(), identity.subject());
    if (prior.isPresent()
        || identities.findByUser_IdAndProvider(user.getId(), "google").isPresent())
      throw new ApiException(HttpStatus.CONFLICT, "Google identity cannot be linked");
    identities.saveAndFlush(new ExternalIdentity(user, identity));
    user.revokeSessions();
    users.saveAndFlush(user);
    SecurityEvents.record(SecurityEvents.Event.GOOGLE_LINKED, user.getId());
    return new Identity(user.getId(), user.getCredentialVersion());
  }

  @Transactional
  public Identity googleIdentity(UUID owner, ValidatedExternalIdentity identity) {
    var user = lock(owner);
    var binding =
        identities
            .findByIssuerAndProviderSubject(identity.issuer(), identity.subject())
            .orElseThrow(AccountSecurityService::unauthorized);
    if (!binding.getUser().getId().equals(owner)) throw unauthorized();
    return new Identity(owner, user.getCredentialVersion());
  }

  @Transactional
  public void unlink(UUID owner, String password) {
    var user = lock(owner);
    if (user.getPasswordHash() == null)
      throw new ApiException(HttpStatus.CONFLICT, "Cannot remove your final sign-in method");
    proof(user, password);
    var binding =
        identities
            .findByUser_IdAndProvider(owner, "google")
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Google is not linked"));
    identities.delete(binding);
    identities.flush();
    user.revokeSessions();
    users.saveAndFlush(user);
    SecurityEvents.record(SecurityEvents.Event.GOOGLE_UNLINKED, owner);
  }

  @Transactional
  public void changePassword(UUID owner, String current, String replacement) {
    var user = lock(owner);
    proof(user, current);
    if (replacement == null
        || replacement.length() < 12
        || replacement.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "Password must contain at least 12 characters and fit within 72 UTF-8 bytes");
    if (encoder.matches(replacement, user.getPasswordHash()))
      throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a different password");
    user.changePassword(encoder.encode(replacement));
    users.saveAndFlush(user);
    SecurityEvents.record(SecurityEvents.Event.PASSWORD_CHANGED, owner);
  }
}

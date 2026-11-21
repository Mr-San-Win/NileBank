package com.nilebank.auth;

import com.nilebank.common.ApiException;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;

/**
 * Internal trust-boundary input, never an HTTP request DTO. A future OIDC adapter must verify
 * signature, issuer, audience, timestamps, nonce and authorization response BEFORE constructing it.
 * This type validates claim shape/policy, not token authenticity. There is no public claims
 * endpoint.
 */
public final class ValidatedExternalIdentity {
  private final String issuer;
  private final String subject;

  @NotBlank
  @Email
  @Size(max = 254)
  private final String email;

  private final String fullName;

  private ValidatedExternalIdentity(String issuer, String subject, String email, String fullName) {
    this.issuer = issuer;
    this.subject = subject;
    this.email = email;
    this.fullName = fullName;
  }

  /** Called only by trusted server-side authentication code after Google OIDC validation. */
  public static ValidatedExternalIdentity google(
      String issuer, String subject, String email, boolean emailVerified, String fullName) {
    if (!"https://accounts.google.com".equals(issuer)
        || subject == null
        || subject.isBlank()
        || subject.length() > 255
        || email == null
        || email.isBlank()
        || !emailVerified
        || fullName == null
        || fullName.isBlank()
        || fullName.strip().length() > 100) {
      throw invalid();
    }
    return new ValidatedExternalIdentity(
        issuer, subject, AuthService.normalize(email), fullName.strip());
  }

  static ApiException invalid() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "External authentication failed");
  }

  public String provider() {
    return "google";
  }

  public String issuer() {
    return issuer;
  }

  public String subject() {
    return subject;
  }

  public String email() {
    return email;
  }

  public String fullName() {
    return fullName;
  }
}

package com.nilebank.auth;

import java.time.Instant;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/** Runs after Spring's signature, issuer, audience, nonce and UserInfo validation. */
@Component
public class OidcIdentityMapper
    implements Converter<OAuth2LoginAuthenticationToken, OAuth2AuthenticationToken> {
  private final ExternalIdentityService identities;
  private final GoogleReauthentication reauthentication;
  private final com.nilebank.auth.mfa.MfaService mfa;
  private final AccountSecurityService security;

  public OidcIdentityMapper(
      ExternalIdentityService identities,
      AccountSecurityService security,
      com.nilebank.auth.mfa.MfaService mfa,
      GoogleReauthentication reauthentication) {
    this.identities = identities;
    this.security = security;
    this.mfa = mfa;
    this.reauthentication = reauthentication;
  }

  @Override
  public OAuth2AuthenticationToken convert(OAuth2LoginAuthenticationToken token) {
    try {
      var validated = (OidcUser) token.getPrincipal();
      var claims =
          ValidatedExternalIdentity.google(
              validated.getIdToken().getIssuer().toExternalForm(),
              validated.getIdToken().getSubject(),
              validated.getEmail(),
              Boolean.TRUE.equals(validated.getEmailVerified()),
              validated.getFullName());
      Object pending =
          token
              .getAuthorizationExchange()
              .getAuthorizationRequest()
              .getAttribute(LinkAuthorizationRequestResolver.LINK);
      AccountSecurityService.Identity identity;
      Object reauth =
          token
              .getAuthorizationExchange()
              .getAuthorizationRequest()
              .getAttribute(GoogleReauthentication.REQUEST_INTENT);
      GoogleReauthentication.Proof proof = null;
      if (reauth != null) {
        if (pending != null || !(reauth instanceof GoogleReauthentication.Intent intent))
          throw ValidatedExternalIdentity.invalid();
        proof =
            reauthentication.validate(
                intent,
                claims,
                validated.getIdToken().getClaimAsInstant("auth_time"),
                SecurityContextHolder.getContext().getAuthentication());
        identity = security.googleIdentity(intent.owner(), claims);
        if (identity.version() != intent.version()) throw ValidatedExternalIdentity.invalid();
      } else if (pending != null) {
        var current = SecurityContextHolder.getContext().getAuthentication();
        if (!(pending instanceof AccountSecurityService.LinkIntent intent)
            || !intent.expiresAt().isAfter(Instant.now())
            || current == null
            || current.getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals("ROLE_USER"))
            || !current.getName().equals(intent.owner().toString()))
          throw ValidatedExternalIdentity.invalid();
        identity = security.link(intent, claims);
      } else {
        var user = identities.resolveOrProvision(claims);
        identity = security.googleIdentity(user.id(), claims);
      }
      OidcUser principal = new ApplicationOidcUser(identity.id(), identity.version(), validated);
      if (proof != null) principal = new ReauthenticatedOidcUser(principal, proof);
      return (OAuth2AuthenticationToken)
          mfa.gate(
              new OAuth2AuthenticationToken(
                  principal,
                  principal.getAuthorities(),
                  token.getClientRegistration().getRegistrationId()));
    } catch (com.nilebank.common.ApiException
        | org.springframework.dao.DataAccessException
        | org.springframework.core.convert.ConversionException
        | IllegalArgumentException rejected) {
      throw new OAuth2AuthenticationException(
          new OAuth2Error("external_identity_rejected"), "External sign-in failed");
    }
  }
}

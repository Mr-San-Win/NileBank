package com.nilebank.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Copies only a server-created, fresh password-proof intent into the session-stored OAuth state.
 */
public final class LinkAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
  public static final String LINK = "nilebank.validatedLinkIntent";
  private final DefaultOAuth2AuthorizationRequestResolver delegate;

  public LinkAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
    delegate =
        new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    return bind(request, delegate.resolve(request));
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String id) {
    return bind(request, delegate.resolve(request, id));
  }

  private OAuth2AuthorizationRequest bind(
      HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
    if (authorization == null) return null;
    var session = request.getSession(false);
    Object reauth =
        session == null ? null : session.getAttribute(GoogleReauthentication.SESSION_INTENT);
    if (reauth != null) {
      session.removeAttribute(GoogleReauthentication.SESSION_INTENT);
      var auth = SecurityContextHolder.getContext().getAuthentication();
      if (!(reauth instanceof GoogleReauthentication.Intent intent)
          || !intent.expires().isAfter(Instant.now())
          || auth == null
          || !auth.getName().equals(intent.owner().toString())
          || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_USER")))
        throw new OAuth2AuthenticationException(
            new OAuth2Error("invalid_reauthentication_intent"), "Verification request expired");
      return OAuth2AuthorizationRequest.from(authorization)
          .additionalParameters(
              parameters -> {
                parameters.put("prompt", "select_account");
                parameters.put("claims", "{\"id_token\":{\"auth_time\":{\"essential\":true}}}");
              })
          .attributes(attributes -> attributes.put(GoogleReauthentication.REQUEST_INTENT, reauth))
          .build();
    }
    Object value =
        session == null ? null : session.getAttribute(AccountSecurityController.PENDING_LINK);
    if (value == null) return authorization;
    session.removeAttribute(AccountSecurityController.PENDING_LINK);
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(value instanceof AccountSecurityService.LinkIntent intent)
        || !intent.expiresAt().isAfter(Instant.now())
        || auth == null
        || !auth.getName().equals(intent.owner().toString()))
      throw new OAuth2AuthenticationException(
          new OAuth2Error("invalid_link_intent"), "Link request expired");
    return OAuth2AuthorizationRequest.from(authorization)
        .additionalParameters(parameters -> parameters.put("prompt", "select_account"))
        .attributes(attributes -> attributes.put(LINK, value))
        .build();
  }
}

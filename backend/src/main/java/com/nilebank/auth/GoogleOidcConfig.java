package com.nilebank.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@Configuration
@ConditionalOnProperty(prefix = "nilebank.auth.google", name = "enabled", havingValue = "true")
public class GoogleOidcConfig {
  @Bean
  ClientRegistrationRepository googleRegistrations(Environment environment) {
    String id = required(environment, "client-id");
    String secret = required(environment, "client-secret");
    String redirect = required(environment, "redirect-uri");
    if (!redirect.startsWith("https://")
        && !redirect.equals("http://localhost:8080/login/oauth2/code/google"))
      throw new IllegalStateException(
          "Google redirect must use HTTPS or the documented localhost callback");
    var registration =
        ClientRegistrations.fromIssuerLocation("https://accounts.google.com")
            .registrationId("google")
            .clientName("Google")
            .clientId(id)
            .clientSecret(secret)
            .scope("openid", "email", "profile")
            .redirectUri(redirect)
            .clientSettings(
                ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
            .build();
    return new InMemoryClientRegistrationRepository(registration);
  }

  private String required(Environment environment, String property) {
    String value = environment.getProperty("nilebank.auth.google." + property);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Google login configuration is incomplete");
    return value;
  }

  @Bean
  OAuth2UserService<OidcUserRequest, OidcUser> applicationOidcUsers(
      ExternalIdentityService identities) {
    var delegate = new OidcUserService();
    return request -> {
      var validated = delegate.loadUser(request);
      try {
        var claims =
            ValidatedExternalIdentity.google(
                request.getIdToken().getIssuer().toExternalForm(),
                request.getIdToken().getSubject(),
                validated.getEmail(),
                Boolean.TRUE.equals(validated.getEmailVerified()),
                validated.getFullName());
        return new ApplicationOidcUser(identities.resolveOrProvision(claims).id(), validated);
      } catch (com.nilebank.common.ApiException
          | org.springframework.dao.DataAccessException rejected) {
        throw new OAuth2AuthenticationException(
            new OAuth2Error("external_identity_rejected"), "External sign-in failed");
      }
    };
  }
}

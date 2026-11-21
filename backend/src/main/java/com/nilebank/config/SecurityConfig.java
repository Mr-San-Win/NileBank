package com.nilebank.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.auth.AuthService;
import com.nilebank.common.ApiError;
import com.nilebank.user.UserRepository;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwords() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  UserDetailsService userDetails(UserRepository users) {
    return email ->
        users
            .findByEmail(AuthService.normalize(email))
            .filter(u -> u.getPasswordHash() != null)
            .map(
                u ->
                    User.withUsername(u.getId().toString())
                        .password(u.getPasswordHash())
                        .roles("USER")
                        .build())
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
  }

  @Bean
  AuthenticationManager authenticationManager(
      UserDetailsService details, PasswordEncoder passwords) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(details);
    provider.setPasswordEncoder(passwords);
    return new ProviderManager(provider);
  }

  @Bean
  SecurityContextRepository contexts() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  CsrfTokenRepository csrfTokens() {
    return new HttpSessionCsrfTokenRepository();
  }

  @Bean
  SessionAuthenticationStrategy loginSessionStrategy(CsrfTokenRepository csrfTokens) {
    return new CompositeSessionAuthenticationStrategy(
        List.of(
            new ChangeSessionIdAuthenticationStrategy(),
            new CsrfAuthenticationStrategy(csrfTokens)));
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      SecurityContextRepository contexts,
      CsrfTokenRepository csrfTokens,
      ObjectMapper json,
      SessionAuthenticationStrategy loginSessions,
      ObjectProvider<ClientRegistrationRepository> registrations,
      ObjectProvider<OAuth2UserService<OidcUserRequest, OidcUser>> oidcUsers)
      throws Exception {
    if (registrations.getIfAvailable() != null) {
      http.oauth2Login(
          oauth ->
              oauth
                  .clientRegistrationRepository(registrations.getObject())
                  .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                  .userInfoEndpoint(info -> info.oidcUserService(oidcUsers.getObject()))
                  .successHandler((request, response, authentication) -> response.sendRedirect("/"))
                  .failureHandler(
                      (request, response, exception) ->
                          response.sendRedirect("/?authError=google")));
    }
    http.sessionManagement(session -> session.sessionAuthenticationStrategy(loginSessions));
    http.securityContext(c -> c.securityContextRepository(contexts))
        .csrf(c -> c.csrfTokenRepository(csrfTokens))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/",
                        "/index.html",
                        "/styles.css",
                        "/app.js",
                        "/extensions.js",
                        "/favicon.ico",
                        "/api/v1/health",
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/options",
                        "/oauth2/authorization/google",
                        "/login/oauth2/code/google",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> {
                          res.setStatus(401);
                          res.setContentType("application/json");
                          json.writeValue(res.getOutputStream(), ApiError.of(401, "Please log in"));
                        })
                    .accessDeniedHandler(
                        (req, res, ex) -> {
                          res.setStatus(403);
                          res.setContentType("application/json");
                          json.writeValue(
                              res.getOutputStream(),
                              ApiError.of(
                                  403, "Request denied; refresh your session and CSRF token"));
                        }))
        .logout(
            l ->
                l.logoutUrl("/api/v1/auth/logout")
                    .deleteCookies("JSESSIONID")
                    .logoutSuccessHandler((req, res, a) -> res.setStatus(204)))
        .headers(
            h ->
                h.contentSecurityPolicy(
                        c ->
                            c.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self'; img-src"
                                    + " 'self' data:; object-src 'none'; base-uri 'self';"
                                    + " frame-ancestors 'none'; form-action 'self'"))
                    .frameOptions(f -> f.deny()));
    return http.build();
  }
}

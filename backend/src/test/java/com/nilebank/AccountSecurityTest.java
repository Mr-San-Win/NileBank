package com.nilebank;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.auth.*;
import com.nilebank.common.ApiException;
import com.nilebank.user.UserRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@org.junit.jupiter.api.extension.ExtendWith(
    org.springframework.boot.test.system.OutputCaptureExtension.class)
class AccountSecurityTest extends PostgresTest {
  @Autowired AccountSecurityService security;
  @Autowired AuthService auth;
  @Autowired ExternalIdentityService external;
  @Autowired ExternalIdentityRepository identities;
  @Autowired UserRepository users;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  static final String PASSWORD = "Example-passphrase-21";

  String email() {
    return UUID.randomUUID() + "@example.test";
  }

  AuthService.UserView user() {
    return auth.register("Security Demo", email(), PASSWORD);
  }

  ValidatedExternalIdentity google() {
    return ValidatedExternalIdentity.google(
        "https://accounts.google.com", UUID.randomUUID().toString(), email(), true, "Google Demo");
  }

  MockHttpSession login(String email, String password) throws Exception {
    var result =
        mvc.perform(
                post("/api/v1/auth/login")
                    .session(new MockHttpSession())
                    .with(csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of("email", email, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
    return (MockHttpSession) result.getRequest().getSession(false);
  }

  void fails(HttpStatus status, Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(status));
  }

  @Test
  void methodVisibilityIsAuthenticatedAndContainsNoIdentitySecrets() throws Exception {
    mvc.perform(get("/api/v1/auth/security")).andExpect(status().isUnauthorized());
    var user = user();
    var session = login(user.email(), PASSWORD);
    mvc.perform(get("/api/v1/auth/security").session(session))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    "{\"password\":true,\"google\":false,\"recoveryAvailable\":false,\"mfaAvailable\":false}"))
        .andExpect(jsonPath("$.passwordHash").doesNotExist())
        .andExpect(jsonPath("$.subject").doesNotExist());
  }

  @Test
  void wrongPasswordCannotPrepareLinkOrChangeCredentials() {
    var user = user();
    fails(HttpStatus.UNAUTHORIZED, () -> security.prepareLink(user.id(), "wrong"));
    fails(
        HttpStatus.UNAUTHORIZED,
        () -> security.changePassword(user.id(), "wrong", "Replacement-password-22"));
    assertThat(users.findById(user.id()).orElseThrow().getCredentialVersion()).isZero();
  }

  @Test
  void explicitLinkPreservesOwnerAndProfileWithoutUsingGoogleEmailForResolution() {
    var user = user();
    var other = user();
    var google =
        ValidatedExternalIdentity.google(
            "https://accounts.google.com",
            UUID.randomUUID().toString(),
            other.email(),
            true,
            "Other Google Name");
    var linked = security.link(security.prepareLink(user.id(), PASSWORD), google);
    assertThat(linked.id()).isEqualTo(user.id());
    assertThat(linked.version()).isEqualTo(1);
    assertThat(auth.me(user.id()).email()).isEqualTo(user.email());
    assertThat(auth.me(user.id()).fullName()).isEqualTo(user.fullName());
    assertThat(security.methods(other.id()).google()).isFalse();
    assertThat(
            identities
                .findByIssuerAndProviderSubject(google.issuer(), google.subject())
                .orElseThrow()
                .getUser()
                .getId())
        .isEqualTo(user.id());
  }

  @Test
  void identityOwnedByAnotherUserCannotBeLinked() {
    var claims = google();
    var owner = external.resolveOrProvision(claims);
    var attacker = user();
    fails(
        HttpStatus.CONFLICT,
        () -> security.link(security.prepareLink(attacker.id(), PASSWORD), claims));
    assertThat(security.methods(attacker.id()).google()).isFalse();
    assertThat(
            identities
                .findByIssuerAndProviderSubject(claims.issuer(), claims.subject())
                .orElseThrow()
                .getUser()
                .getId())
        .isEqualTo(owner.id());
  }

  @Test
  void expiredReauthenticationAndCredentialVersionChangesRejectLinking() {
    var user = user();
    var intent = security.prepareLink(user.id(), PASSWORD);
    fails(
        HttpStatus.UNAUTHORIZED,
        () ->
            security.link(
                new AccountSecurityService.LinkIntent(
                    user.id(), intent.version(), Instant.now().minusSeconds(1)),
                google()));
    security.changePassword(user.id(), PASSWORD, "Replacement-password-22");
    fails(HttpStatus.UNAUTHORIZED, () -> security.link(intent, google()));
    assertThat(security.methods(user.id()).google()).isFalse();
  }

  @Test
  void unlinkingLastMethodIsRejected() {
    var user = external.resolveOrProvision(google());
    fails(HttpStatus.CONFLICT, () -> security.unlink(user.id(), PASSWORD));
    assertThat(security.methods(user.id()).google()).isTrue();
  }

  @Test
  void unlinkKeepsPasswordAndRevokesExistingCredentials() {
    var user = user();
    security.link(security.prepareLink(user.id(), PASSWORD), google());
    security.unlink(user.id(), PASSWORD);
    assertThat(security.methods(user.id()).google()).isFalse();
    assertThat(security.methods(user.id()).password()).isTrue();
    assertThat(users.findById(user.id()).orElseThrow().getCredentialVersion()).isEqualTo(2);
  }

  @Test
  void externalOnlyUserCannotInventALocalPassword() {
    var user = external.resolveOrProvision(google());
    fails(
        HttpStatus.UNAUTHORIZED,
        () -> security.changePassword(user.id(), PASSWORD, "Replacement-password-22"));
    assertThat(users.findById(user.id()).orElseThrow().getPasswordHash()).isNull();
  }

  @Test
  void csrfIsRequiredForCredentialChanges() throws Exception {
    var user = user();
    var session = login(user.email(), PASSWORD);
    mvc.perform(
            post("/api/v1/auth/security/password")
                .session(session)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "currentPassword",
                            PASSWORD,
                            "newPassword",
                            "Replacement-password-22"))))
        .andExpect(status().isForbidden());
    assertThat(users.findById(user.id()).orElseThrow().getCredentialVersion()).isZero();
  }

  @Test
  void passwordChangeInvalidatesCurrentAndOtherSessionsAndOldPassword() throws Exception {
    var user = user();
    var one = login(user.email(), PASSWORD);
    var two = login(user.email(), PASSWORD);
    mvc.perform(
            post("/api/v1/auth/security/password")
                .session(one)
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "currentPassword",
                            PASSWORD,
                            "newPassword",
                            "Replacement-password-22"))))
        .andExpect(status().isNoContent());
    assertThat(one.isInvalid()).isTrue();
    mvc.perform(get("/api/v1/auth/me").session(two)).andExpect(status().isUnauthorized());
    assertThat(two.isInvalid()).isTrue();
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(Map.of("email", user.email(), "password", PASSWORD))))
        .andExpect(status().isUnauthorized());
    var fresh = login(user.email(), "Replacement-password-22");
    mvc.perform(get("/api/v1/auth/me").session(fresh)).andExpect(status().isOk());
  }

  @Test
  void revokedSessionCanStillLoadLoginPageAndStaticModules() throws Exception {
    var user = user();
    for (String path : new String[] {"/", "/app.js", "/savings.js", "/api/v1/auth/csrf"}) {
      var session = login(user.email(), PASSWORD);
      security.changePassword(user.id(), PASSWORD, "Replacement-password-22");
      mvc.perform(get(path).session(session)).andExpect(status().isOk());
      assertThat(session.isInvalid()).isTrue();
      security.changePassword(user.id(), "Replacement-password-22", PASSWORD);
    }
  }

  @Test
  void blankOversizedAndSamePasswordsAreRejected() {
    var user = user();
    for (String replacement : Arrays.asList(null, "short", PASSWORD, "ก".repeat(30)))
      fails(
          HttpStatus.BAD_REQUEST, () -> security.changePassword(user.id(), PASSWORD, replacement));
    assertThat(users.findById(user.id()).orElseThrow().getCredentialVersion()).isZero();
  }

  @Test
  void concurrentBindingsCannotStealAnIdentity() throws Exception {
    var first = user();
    var second = user();
    var claims = google();
    var intents =
        List.of(
            security.prepareLink(first.id(), PASSWORD),
            security.prepareLink(second.id(), PASSWORD));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (var intent : intents)
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  try {
                    security.link(intent, claims);
                    return true;
                  } catch (ApiException
                      | org.springframework.dao.DataIntegrityViolationException conflict) {
                    return false;
                  }
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      int count = 0;
      for (var result : results) if (result.get(30, TimeUnit.SECONDS)) count++;
      assertThat(count).isEqualTo(1);
    }
    int count =
        (security.methods(first.id()).google() ? 1 : 0)
            + (security.methods(second.id()).google() ? 1 : 0);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void securityLogsDoNotIncludePasswordsOrEmail(
      org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    var user = user();
    int start = output.getAll().length();
    security.changePassword(user.id(), PASSWORD, "Private-test-replacement-31");
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("email", user.email(), "password", "Private-wrong-password-31"))))
        .andExpect(status().isUnauthorized());
    String events = output.getAll().substring(start);
    assertThat(events).contains("PASSWORD_CHANGED", "LOGIN_FAILED");
    assertThat(events)
        .doesNotContain(
            PASSWORD, "Private-test-replacement-31", "Private-wrong-password-31", user.email());
  }

  @Test
  void malformedPrincipalFailsClosedWithoutBreakingPublicHealth() throws Exception {
    mvc.perform(
            get("/api/v1/auth/me")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("not-a-uuid")))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/health")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("health-probe")))
        .andExpect(status().isOk());
  }
}

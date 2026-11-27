package com.nilebank;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.auth.*;
import com.nilebank.auth.recovery.*;
import com.nilebank.auth.verification.*;
import com.nilebank.user.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

class EmailVerificationTest extends PostgresTest {
  static final LocalSmtpSink smtp = new LocalSmtpSink();

  @DynamicPropertySource
  static void mail(DynamicPropertyRegistry r) {
    r.add("nilebank.auth.verification.enabled", () -> true);
    r.add("nilebank.auth.recovery.enabled", () -> true);
    r.add("nilebank.auth.recovery.smtp-host", () -> "127.0.0.1");
    r.add("nilebank.auth.recovery.smtp-port", smtp::port);
    r.add("nilebank.auth.recovery.smtp-starttls", () -> false);
    r.add("nilebank.auth.recovery.from", () -> "verification@example.test");
  }

  @Autowired EmailVerificationService verification;
  @Autowired PasswordRecoveryService recovery;
  @Autowired AuthService auth;
  @Autowired AccountSecurityService security;
  @Autowired UserRepository users;
  @Autowired ExternalIdentityService external;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  static final String PASSWORD = "Fictional-verification-password-2026";

  AuthService.UserView user() {
    return auth.register("Verification Demo", UUID.randomUUID() + "@example.test", PASSWORD);
  }

  String token(AuthService.UserView user) {
    verification.request(user.id());
    return delivered(user, "verify", "email_verification_tokens");
  }

  String delivered(AuthService.UserView user, String purpose, String table) {
    await()
        .atMost(Duration.ofSeconds(12))
        .until(
            () ->
                smtp.messages.stream()
                    .anyMatch(m -> m.contains(user.email()) && m.contains("#" + purpose + "=")));
    String body =
        smtp.messages.stream()
            .filter(m -> m.contains(user.email()) && m.contains("#" + purpose + "="))
            .findFirst()
            .orElseThrow();
    var match =
        java.util.regex.Pattern.compile("#" + purpose + "=([A-Za-z0-9_-]{43})").matcher(body);
    assertThat(match.find()).isTrue();
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                jdbc.queryForObject(
                        "select count(*) from "
                            + table
                            + " where user_id=? and delivered_at is not null",
                        Long.class,
                        user.id())
                    == 1);
    return match.group(1);
  }

  @Test
  void deliveredTokenConfirmsEmailOnceWithoutChangingIdentityCredentialsOrMethods() {
    var user = user();
    String token = token(user);
    String hash = users.findById(user.id()).orElseThrow().getPasswordHash();
    verification.confirm(token);
    assertThat(verification.status(user.id()).verified()).isTrue();
    var after = users.findById(user.id()).orElseThrow();
    assertThat(after.getEmail()).isEqualTo(user.email());
    assertThat(after.getPasswordHash()).isEqualTo(hash);
    assertThat(after.getCredentialVersion()).isZero();
    assertThatThrownBy(() -> verification.confirm(token))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    assertThat(
            jdbc.queryForObject(
                "select token_hash from email_verification_tokens where user_id=?",
                String.class,
                user.id()))
        .hasSize(64)
        .isNotEqualTo(token);
  }

  @Test
  void wrongExpiredAndCredentialRevokedLinksFail() {
    var user = user();
    String token = token(user);
    assertThatThrownBy(() -> verification.confirm("A".repeat(43)))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    jdbc.update(
        "update email_verification_tokens set created_at=current_timestamp-interval '2"
            + " days',expires_at=current_timestamp-interval '1 minute' where user_id=?",
        user.id());
    assertThatThrownBy(() -> verification.confirm(token))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    var other = user();
    String second = token(other);
    security.changePassword(other.id(), PASSWORD, "Replacement-verification-password-2026");
    assertThatThrownBy(() -> verification.confirm(second))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    assertThat(verification.status(other.id()).verified()).isFalse();
  }

  @Test
  void verificationAndRecoveryTokensCannotBeSubstituted() {
    var user = user();
    String verify = token(user);
    recovery.request(user.email());
    String reset = delivered(user, "reset", "password_reset_tokens");
    assertThatThrownBy(() -> recovery.reset(verify, "Replacement-password-2026"))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    assertThatThrownBy(() -> verification.confirm(reset))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    assertThat(verification.status(user.id()).verified()).isFalse();
    verification.confirm(verify);
  }

  @Test
  void concurrentConfirmationHasOneWinner() throws Exception {
    var user = user();
    String token = token(user);
    var start = new CountDownLatch(1);
    int success = 0;
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    verification.confirm(token);
                    return true;
                  } catch (com.nilebank.common.ApiException conflict) {
                    return false;
                  }
                }));
      start.countDown();
      for (var r : results) if (r.get(20, TimeUnit.SECONDS)) success++;
    }
    assertThat(success).isEqualTo(1);
  }

  @Test
  void failedDeliveryCannotVerifyAndDoesNotChangeProfile() {
    var user = user();
    smtp.rejected.add(user.email());
    int before = smtp.failures.get();
    verification.request(user.id());
    await().atMost(Duration.ofSeconds(10)).until(() -> smtp.failures.get() > before);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_verification_tokens where user_id=? and delivered_at is"
                    + " not null",
                Long.class,
                user.id()))
        .isZero();
    assertThat(verification.status(user.id()).verified()).isFalse();
  }

  @Test
  void requestIsOwnedAuthenticatedCsrfProtectedAndNeverReturnsLink() throws Exception {
    var a = user();
    var b = user();
    mvc.perform(post("/api/v1/auth/security/email/request").with(csrf()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/security/email/request")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user(a.id().toString())))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/auth/security/email/request")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user(a.id().toString()))
                .with(csrf()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.token").doesNotExist());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from email_verification_tokens where user_id=?",
                Long.class,
                b.id()))
        .isZero();
  }

  @Test
  void anonymousConfirmationNeedsCsrfAndCreatesNoAuthenticatedSession() throws Exception {
    var a = user();
    String token = token(a);
    String body = json.writeValueAsString(Map.of("token", token));
    mvc.perform(post("/api/v1/auth/email/confirm").contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/auth/email/confirm")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    assertThat(verification.status(a.id()).verified()).isTrue();
  }

  @Test
  void confirmingGoogleOnlyEmailNeverCreatesPasswordOrLinksAnotherIdentity() {
    var user =
        external.resolveOrProvision(
            ValidatedExternalIdentity.google(
                "https://accounts.google.com",
                UUID.randomUUID().toString(),
                UUID.randomUUID() + "@example.test",
                true,
                "Google Verification"));
    verification.confirm(token(user));
    assertThat(users.findById(user.id()).orElseThrow().getPasswordHash()).isNull();
    assertThat(security.methods(user.id()).google()).isTrue();
    assertThat(security.methods(user.id()).password()).isFalse();
  }

  @Test
  void snapshotMismatchCannotConfirmDifferentEmail() {
    var user = user();
    String token = token(user);
    jdbc.update(
        "update email_verification_tokens set email_snapshot='different@example.test' where"
            + " user_id=?",
        user.id());
    assertThatThrownBy(() -> verification.confirm(token))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    assertThat(verification.status(user.id()).verified()).isFalse();
  }
}

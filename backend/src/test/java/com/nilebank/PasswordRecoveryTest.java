package com.nilebank;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.auth.*;
import com.nilebank.auth.recovery.*;
import com.nilebank.user.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@org.junit.jupiter.api.extension.ExtendWith(
    org.springframework.boot.test.system.OutputCaptureExtension.class)
class PasswordRecoveryTest extends PostgresTest {
  static final LocalSmtpSink smtp = new LocalSmtpSink();

  @DynamicPropertySource
  static void mail(DynamicPropertyRegistry r) {
    r.add("nilebank.auth.recovery.enabled", () -> true);
    r.add("nilebank.auth.recovery.smtp-host", () -> "127.0.0.1");
    r.add("nilebank.auth.recovery.smtp-port", smtp::port);
    r.add("nilebank.auth.recovery.smtp-starttls", () -> false);
    r.add("nilebank.auth.recovery.from", () -> "recovery@example.test");
  }

  @Autowired PasswordRecoveryService recovery;
  @Autowired AuthService auth;
  @Autowired AccountSecurityService security;
  @Autowired ExternalIdentityService external;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired PasswordEncoder encoder;
  @Autowired AuthenticationManager manager;
  @Autowired com.nilebank.account.AccountService accounts;
  @Autowired com.nilebank.transaction.BankingService banking;
  static final String ORIGINAL = "Fictional-original-password-2026";
  static final String REPLACEMENT = "Fictional-replacement-password-2026";

  AuthService.UserView user() {
    return auth.register("Recovery Demo", UUID.randomUUID() + "@example.test", ORIGINAL);
  }

  String token(AuthService.UserView user) {
    recovery.request(user.email());
    await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> smtp.messages.stream().anyMatch(m -> m.contains(user.email())));
    String body =
        smtp.messages.stream().filter(m -> m.contains(user.email())).findFirst().orElseThrow();
    var match = java.util.regex.Pattern.compile("#reset=([A-Za-z0-9_-]{43})").matcher(body);
    assertThat(match.find()).isTrue();
    String token = match.group(1);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "select count(*) from password_reset_tokens where user_id=? and"
                                + " delivered_at is not null",
                            Long.class,
                            user.id()))
                    .isEqualTo(1));
    return token;
  }

  void invalid(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOf(com.nilebank.common.ApiException.class);
  }

  @Test
  void actualSmtpDeliveryContainsFragmentLinkAndOnlyHashIsStored() {
    var user = user();
    var token = token(user);
    assertThat(
            smtp.messages.stream().filter(m -> m.contains(user.email())).findFirst().orElseThrow())
        .contains("http://localhost:8080/#reset=")
        .doesNotContain(ORIGINAL);
    var stored = jdbc.queryForMap("select * from password_reset_tokens where user_id=?", user.id());
    assertThat(stored.get("token_hash").toString()).hasSize(64).doesNotContain(token);
    assertThat(users.findById(user.id()).orElseThrow().getCredentialVersion()).isZero();
  }

  @Test
  void resetPreservesInternalIdentityLinkedGoogleAndFinancialRecords() {
    var user = user();
    var identity =
        ValidatedExternalIdentity.google(
            "https://accounts.google.com",
            UUID.randomUUID().toString(),
            UUID.randomUUID() + "@example.test",
            true,
            "Google Demo");
    security.link(security.prepareLink(user.id(), ORIGINAL), identity);
    var account = accounts.create(user.id());
    banking.deposit(user.id(), account.id(), new java.math.BigDecimal("100"));
    recovery.reset(token(user), REPLACEMENT);
    assertThat(auth.me(user.id()).email()).isEqualTo(user.email());
    assertThat(auth.me(user.id()).fullName()).isEqualTo(user.fullName());
    assertThat(accounts.mine(user.id()).id()).isEqualTo(account.id());
    assertThat(accounts.mine(user.id()).balance()).isEqualByComparingTo("100");
    assertThat(banking.history(user.id(), 0, 10).totalElements()).isEqualTo(1);
    assertThat(security.methods(user.id()).google()).isTrue();
  }

  @Test
  void tokenChangesPasswordOnceAndRejectsReplay() {
    var user = user();
    String token = token(user);
    recovery.reset(token, REPLACEMENT);
    assertThat(
            encoder.matches(REPLACEMENT, users.findById(user.id()).orElseThrow().getPasswordHash()))
        .isTrue();
    invalid(() -> recovery.reset(token, "Different-password-2026"));
    assertThat(users.findById(user.id()).orElseThrow().getCredentialVersion()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                manager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(user.email(), ORIGINAL)))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void expiredAndTamperedTokensCannotChangeCredentials() {
    var user = user();
    String token = token(user);
    invalid(() -> recovery.reset("invalid-token", REPLACEMENT));
    invalid(
        () ->
            recovery.reset((token.startsWith("A") ? "B" : "A") + token.substring(1), REPLACEMENT));
    jdbc.update(
        "update password_reset_tokens set created_at=current_timestamp-interval '20"
            + " minutes',expires_at=current_timestamp-interval '1 minute' where user_id=?",
        user.id());
    invalid(() -> recovery.reset(token, REPLACEMENT));
    assertThat(users.findById(user.id()).orElseThrow().getCredentialVersion()).isZero();
  }

  @Test
  void invalidPasswordsDoNotConsumeToken() {
    var user = user();
    String token = token(user);
    for (String password : List.of("short", "ก".repeat(30), ORIGINAL))
      invalid(() -> recovery.reset(token, password));
    recovery.reset(token, REPLACEMENT);
    assertThat(users.findById(user.id()).orElseThrow().getCredentialVersion()).isEqualTo(1);
  }

  @Test
  void passwordChangeInvalidatesPreviouslyDeliveredLink() {
    var user = user();
    String token = token(user);
    security.changePassword(user.id(), ORIGINAL, REPLACEMENT);
    invalid(() -> recovery.reset(token, "Another-password-2026"));
  }

  @Test
  void concurrentConsumptionHasExactlyOneWinner() throws Exception {
    var user = user();
    String token = token(user);
    var start = new CountDownLatch(1);
    int success = 0;
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        int n = i;
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    recovery.reset(token, REPLACEMENT + n);
                    return true;
                  } catch (com.nilebank.common.ApiException rejected) {
                    return false;
                  }
                }));
      }
      start.countDown();
      for (var r : results) if (r.get(30, TimeUnit.SECONDS)) success++;
    }
    assertThat(success).isEqualTo(1);
    assertThat(users.findById(user.id()).orElseThrow().getCredentialVersion()).isEqualTo(1);
  }

  @Test
  void requestResponseIsGenericAndCsrfProtectedForAllProfiles() throws Exception {
    var user = user();
    var google =
        external.resolveOrProvision(
            ValidatedExternalIdentity.google(
                "https://accounts.google.com",
                UUID.randomUUID().toString(),
                UUID.randomUUID() + "@example.test",
                true,
                "Google Demo"));
    String response = null;
    for (String email :
        List.of(user.email(), google.email(), UUID.randomUUID() + "@example.test")) {
      var result =
          mvc.perform(
                  post("/api/v1/auth/recovery/request")
                      .with(csrf())
                      .contentType("application/json")
                      .content(json.writeValueAsString(Map.of("email", email))))
              .andExpect(status().isAccepted())
              .andReturn();
      if (response == null) response = result.getResponse().getContentAsString();
      else assertThat(result.getResponse().getContentAsString()).isEqualTo(response);
    }
    mvc.perform(
            post("/api/v1/auth/recovery/request")
                .contentType("application/json")
                .content("{\"email\":\"unknown@example.test\"}"))
        .andExpect(status().isForbidden());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from password_reset_tokens where user_id=?",
                Long.class,
                google.id()))
        .isZero();
  }

  @Test
  void failedDeliveryCannotBeUsedAndDoesNotChangePassword() {
    var user = user();
    smtp.rejected.add(user.email());
    int before = smtp.failures.get();
    recovery.request(user.email());
    await().atMost(Duration.ofSeconds(10)).until(() -> smtp.failures.get() > before);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from password_reset_tokens where user_id=? and delivered_at is not"
                    + " null",
                Long.class,
                user.id()))
        .isZero();
    assertThat(users.findById(user.id()).orElseThrow().getCredentialVersion()).isZero();
  }

  @Test
  void resetRequiresCsrfAndRevokesOldSession() throws Exception {
    var user = user();
    var result =
        mvc.perform(
                post("/api/v1/auth/login")
                    .session(new MockHttpSession())
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of("email", user.email(), "password", ORIGINAL))))
            .andExpect(status().isOk())
            .andReturn();
    var session = (MockHttpSession) result.getRequest().getSession(false);
    String token = token(user);
    String body = json.writeValueAsString(Map.of("token", token, "password", REPLACEMENT));
    mvc.perform(post("/api/v1/auth/recovery/reset").contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/auth/recovery/reset")
                .with(csrf())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void accountThrottlingDoesNotRevokeAnAlreadyDeliveredLink() {
    var user = user();
    String token = token(user);
    recovery.request(user.email());
    recovery.reset(token, REPLACEMENT);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from password_reset_tokens where user_id=?",
                Long.class,
                user.id()))
        .isEqualTo(1);
  }

  @Test
  void resetLogsDoNotExposeTokenPasswordOrEmail(
      org.springframework.boot.test.system.CapturedOutput output) {
    var user = user();
    String token = token(user);
    int start = output.getAll().length();
    recovery.reset(token, REPLACEMENT);
    assertThat(output.getAll().substring(start))
        .contains("PASSWORD_RESET")
        .doesNotContain(token, user.email(), ORIGINAL, REPLACEMENT);
  }
}

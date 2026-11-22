package com.nilebank;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.account.*;
import com.nilebank.auth.*;
import com.nilebank.auth.mfa.*;
import com.nilebank.auth.recovery.PasswordRecoveryService;
import com.nilebank.user.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;

@org.junit.jupiter.api.extension.ExtendWith(
    org.springframework.boot.test.system.OutputCaptureExtension.class)
class MfaTest extends PostgresTest {
  static final LocalSmtpSink smtp = new LocalSmtpSink();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("nilebank.auth.mfa.enabled", () -> true);
    r.add("nilebank.auth.mfa.active-key-id", () -> "test");
    r.add("nilebank.auth.mfa.keys-json", () -> "{\"test\":\"" + MfaCryptoTest.TEST_KEY + "\"}");
    r.add("nilebank.auth.recovery.enabled", () -> true);
    r.add("nilebank.auth.recovery.smtp-host", () -> "127.0.0.1");
    r.add("nilebank.auth.recovery.smtp-port", smtp::port);
    r.add("nilebank.auth.recovery.smtp-starttls", () -> false);
    r.add("nilebank.auth.recovery.from", () -> "recovery@example.test");
  }

  @Autowired MfaService mfa;
  @Autowired AuthService auth;
  @Autowired AccountService accounts;
  @Autowired ExternalIdentityService external;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired PasswordRecoveryService recovery;
  @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  static final String PASSWORD = "Fictional-MFA-password-2026";

  record Fixture(AuthService.UserView user, String secret, List<String> codes) {}

  Fixture enrolled() {
    var user = auth.register("MFA Demo", UUID.randomUUID() + "@example.test", PASSWORD);
    var enrollment = mfa.begin(user.id(), PASSWORD);
    var codes =
        mfa.confirm(
            user.id(),
            PASSWORD,
            Totp.code(Totp.decode(enrollment.secret()), Instant.now().getEpochSecond() / 30));
    return new Fixture(user, enrollment.secret(), codes);
  }

  String fresh(Fixture f) {
    return Totp.code(Totp.decode(f.secret), Instant.now().getEpochSecond() / 30 + 1);
  }

  long version(Fixture f) {
    return users.findById(f.user.id()).orElseThrow().getCredentialVersion();
  }

  MockHttpSession login(Fixture f) throws Exception {
    return login(f.user.email(), PASSWORD, 202);
  }

  MockHttpSession login(String email, String password, int status) throws Exception {
    var result =
        mvc.perform(
                post("/api/v1/auth/login")
                    .session(new MockHttpSession())
                    .with(csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of("email", email, "password", password))))
            .andExpect(status().is(status))
            .andReturn();
    return (MockHttpSession) result.getRequest().getSession(false);
  }

  MvcResult challenge(MockHttpSession session, String code, int status) throws Exception {
    return mvc.perform(
            post("/api/v1/auth/mfa/challenge")
                .session(session)
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("code", code))))
        .andExpect(status().is(status))
        .andReturn();
  }

  @Test
  void enrollmentStoresNoPlaintextAndCreatesOnlyHashedRecoveryCodes() {
    var f = enrolled();
    var row = jdbc.queryForMap("select * from mfa_settings where user_id=?", f.user.id());
    assertThat(row.get("encrypted_secret").toString()).doesNotContain(f.secret);
    assertThat(row.get("status")).isEqualTo("ACTIVE");
    assertThat(f.codes).hasSize(8).doesNotHaveDuplicates();
    assertThat(mfa.settings(f.user.id()).recoveryCodesRemaining()).isEqualTo(8);
    for (String hash :
        jdbc.queryForList(
            "select code_hash from mfa_recovery_codes where user_id=?", String.class, f.user.id()))
      assertThat(f.codes).noneMatch(c -> c.contains(hash));
  }

  @Test
  void pendingPasswordLoginCannotReachAnyBankingApiAndCompletionRotatesSession() throws Exception {
    var f = enrolled();
    var account = accounts.create(f.user.id());
    var session = login(f);
    String id = session.getId();
    mvc.perform(get("/api/v1/auth/mfa/status").session(session))
        .andExpect(jsonPath("$.required").value(true));
    mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/accounts/me").session(session)).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/transactions/deposit")
                .session(session)
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("accountId", account.id(), "amount", "1"))))
        .andExpect(status().isForbidden());
    challenge(session, fresh(f), 200);
    assertThat(session.getId()).isNotEqualTo(id);
    mvc.perform(get("/api/v1/auth/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(f.user.id().toString()));
    mvc.perform(get("/api/v1/accounts/me").session(session)).andExpect(status().isOk());
    mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
        .andExpect(status().isNoContent());
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void csrfRequiredAndAnonymousChallengeCannotAuthenticate() throws Exception {
    var f = enrolled();
    var session = login(f);
    mvc.perform(
            post("/api/v1/auth/mfa/challenge")
                .session(session)
                .contentType("application/json")
                .content("{\"code\":\"123456\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/auth/mfa/challenge")
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("code", f.codes.getFirst()))))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void otpReplayAndConcurrentUseCannotAuthenticateTwice() throws Exception {
    var f = enrolled();
    String code = fresh(f);
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
                    mfa.verify(f.user.id(), version(f), code);
                    return true;
                  } catch (com.nilebank.common.ApiException rejected) {
                    return false;
                  }
                }));
      start.countDown();
      for (var r : results) if (r.get(30, TimeUnit.SECONDS)) success++;
    }
    assertThat(success).isEqualTo(1);
    assertThatThrownBy(() -> mfa.verify(f.user.id(), version(f), code))
        .isInstanceOf(com.nilebank.common.ApiException.class);
  }

  @Test
  void recoveryCodeReplayAndCrossUserCodesAreRejected() {
    var a = enrolled();
    var b = enrolled();
    mfa.verify(a.user.id(), version(a), a.codes.getFirst());
    assertThatThrownBy(() -> mfa.verify(a.user.id(), version(a), a.codes.getFirst()))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    assertThatThrownBy(() -> mfa.verify(a.user.id(), version(a), b.codes.getFirst()))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    assertThat(mfa.settings(a.user.id()).recoveryCodesRemaining()).isEqualTo(7);
    assertThat(mfa.settings(b.user.id()).recoveryCodesRemaining()).isEqualTo(8);
  }

  @Test
  void concurrentBackupConsumptionHasOneWinner() throws Exception {
    var f = enrolled();
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
                    mfa.verify(f.user.id(), version(f), f.codes.getFirst());
                    return true;
                  } catch (com.nilebank.common.ApiException rejected) {
                    return false;
                  }
                }));
      start.countDown();
      for (var r : results) if (r.get(30, TimeUnit.SECONDS)) success++;
    }
    assertThat(success).isEqualTo(1);
    assertThat(mfa.settings(f.user.id()).recoveryCodesRemaining()).isEqualTo(7);
  }

  @Test
  void failureCountersCommitAndEnforcePerAccountCooldown() {
    var f = enrolled();
    for (int i = 0; i < 10; i++)
      assertThatThrownBy(() -> mfa.verify(f.user.id(), version(f), "invalid"))
          .isInstanceOf(com.nilebank.common.ApiException.class);
    assertThat(
            jdbc.queryForObject(
                "select failed_attempts from mfa_settings where user_id=?",
                Integer.class,
                f.user.id()))
        .isEqualTo(10);
    assertThatThrownBy(() -> mfa.verify(f.user.id(), version(f), fresh(f)))
        .isInstanceOfSatisfying(
            com.nilebank.common.ApiException.class,
            e -> assertThat(e.status().value()).isEqualTo(429));
    jdbc.update(
        "update mfa_settings set failure_window=current_timestamp-interval '61 seconds' where"
            + " user_id=?",
        f.user.id());
    mfa.verify(f.user.id(), version(f), fresh(f));
  }

  @Test
  void pendingStepHasFiveAttemptsAndExpiresWithoutBankingAccess() throws Exception {
    var f = enrolled();
    var session = login(f);
    for (int i = 0; i < 6; i++) challenge(session, "invalid", 400);
    assertThat(session.isInvalid()).isTrue();
    var expired = login(f);
    var context =
        (SecurityContext)
            expired.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    var pending = (MfaService.Pending) context.getAuthentication().getDetails();
    ((org.springframework.security.authentication.AbstractAuthenticationToken)
            context.getAuthentication())
        .setDetails(
            new MfaService.Pending(
                pending.original(),
                Instant.now().minusSeconds(1),
                new java.util.concurrent.atomic.AtomicInteger()));
    mvc.perform(get("/api/v1/auth/me").session(expired)).andExpect(status().isUnauthorized());
    assertThat(expired.isInvalid()).isTrue();
  }

  @Test
  void setupNeedsPasswordAndExpiredOrWrongCodeCannotActivate() {
    var user = auth.register("Setup Demo", UUID.randomUUID() + "@example.test", PASSWORD);
    assertThatThrownBy(() -> mfa.begin(user.id(), "wrong"))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    var pending = mfa.begin(user.id(), PASSWORD);
    assertThatThrownBy(() -> mfa.confirm(user.id(), PASSWORD, "invalid"))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    jdbc.update(
        "update mfa_settings set enrollment_expires_at=current_timestamp-interval '1 second' where"
            + " user_id=?",
        user.id());
    assertThatThrownBy(
            () ->
                mfa.confirm(
                    user.id(),
                    PASSWORD,
                    Totp.code(Totp.decode(pending.secret()), Instant.now().getEpochSecond() / 30)))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    assertThat(mfa.settings(user.id()).enabled()).isFalse();
  }

  @Test
  void disableRequiresFreshFactorAndPasswordAndRevokesSessions() throws Exception {
    var f = enrolled();
    var session = login(f);
    challenge(session, f.codes.getFirst(), 200);
    assertThatThrownBy(() -> mfa.disable(f.user.id(), "wrong", f.codes.get(1), null))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    assertThatThrownBy(() -> mfa.disable(f.user.id(), PASSWORD, "invalid", null))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    mfa.disable(f.user.id(), PASSWORD, f.codes.get(1), null);
    assertThat(mfa.settings(f.user.id()).enabled()).isFalse();
    mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
    login(f.user.email(), PASSWORD, 200);
  }

  @Test
  void lastRecoveryCodeCanManageFactorUsingRecentServerSideMfaProof() throws Exception {
    var f = enrolled();
    for (int i = 0; i < 7; i++) mfa.verify(f.user.id(), version(f), f.codes.get(i));
    var session = login(f);
    challenge(session, f.codes.get(7), 200);
    mvc.perform(
            post("/api/v1/auth/security/mfa/disable")
                .session(session)
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("currentPassword", PASSWORD))))
        .andExpect(status().isNoContent());
    assertThat(mfa.settings(f.user.id()).enabled()).isFalse();
  }

  @Test
  void regeneratedCodesInvalidateOldCodesAndCredentials() {
    var f = enrolled();
    long old = version(f);
    var replacement = mfa.regenerate(f.user.id(), PASSWORD, f.codes.getFirst(), null);
    assertThat(replacement).hasSize(8).doesNotContainAnyElementsOf(f.codes);
    assertThat(version(f)).isEqualTo(old + 1);
    assertThatThrownBy(() -> mfa.verify(f.user.id(), old, replacement.getFirst()))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    assertThatThrownBy(() -> mfa.verify(f.user.id(), version(f), f.codes.get(1)))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    mfa.verify(f.user.id(), version(f), replacement.getFirst());
  }

  @Test
  void passwordRecoveryNeverRemovesMfaOrAuthenticatesWithoutIt() throws Exception {
    var f = enrolled();
    var old = login(f);
    recovery.request(f.user.email());
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> smtp.messages.stream().anyMatch(m -> m.contains(f.user.email())));
    String body =
        smtp.messages.stream().filter(m -> m.contains(f.user.email())).findFirst().orElseThrow();
    var match = java.util.regex.Pattern.compile("#reset=([A-Za-z0-9_-]{43})").matcher(body);
    assertThat(match.find()).isTrue();
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                jdbc.queryForObject(
                        "select count(*) from password_reset_tokens where user_id=? and"
                            + " delivered_at is not null",
                        Long.class,
                        f.user.id())
                    == 1);
    recovery.reset(match.group(1), "Replacement-MFA-password-2026");
    assertThat(mfa.settings(f.user.id()).enabled()).isTrue();
    mvc.perform(get("/api/v1/auth/me").session(old)).andExpect(status().isUnauthorized());
    var session = login(f.user.email(), "Replacement-MFA-password-2026", 202);
    challenge(session, fresh(f), 200);
  }

  @Test
  void googleOnlyUsersCannotInventPasswordForEnrollment() {
    var user =
        external.resolveOrProvision(
            ValidatedExternalIdentity.google(
                "https://accounts.google.com",
                UUID.randomUUID().toString(),
                UUID.randomUUID() + "@example.test",
                true,
                "Google Demo"));
    assertThat(mfa.settings(user.id()).available()).isFalse();
    assertThatThrownBy(() -> mfa.begin(user.id(), PASSWORD))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    assertThat(users.findById(user.id()).orElseThrow().getPasswordHash()).isNull();
  }

  @Test
  void disablingEnrollmentDoesNotBypassExistingMfaAndMissingKeysFailClosed() throws Exception {
    var f = enrolled();
    var session = login(f);
    var context =
        (SecurityContext)
            session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    var original = MfaService.pendingState(context.getAuthentication()).original();
    var disabled =
        new MfaSecrets(
            MfaCryptoTest.environment().withProperty("nilebank.auth.mfa.enabled", "false"), json);
    var service = new MfaService(users, jdbc, passwordEncoder, disabled, transactionManager);
    assertThat(MfaService.pending(service.gate(original))).isTrue();
    var missing =
        new MfaService(
            users,
            jdbc,
            passwordEncoder,
            new MfaSecrets(new org.springframework.mock.env.MockEnvironment(), json),
            transactionManager);
    assertThatThrownBy(() -> missing.gate(original))
        .isInstanceOfSatisfying(
            com.nilebank.common.ApiException.class,
            e -> assertThat(e.status().value()).isEqualTo(503));
  }

  @Test
  void expiredWrongOwnerFutureOrStaleCredentialStampsCannotReplaceFactorProof() {
    var f = enrolled();
    long version = version(f);
    for (var stamp :
        List.of(
            new MfaService.Verified(UUID.randomUUID(), version, Instant.now()),
            new MfaService.Verified(f.user.id(), version - 1, Instant.now()),
            new MfaService.Verified(f.user.id(), version, Instant.now().minusSeconds(301)),
            new MfaService.Verified(f.user.id(), version, Instant.now().plusSeconds(60))))
      assertThatThrownBy(() -> mfa.disable(f.user.id(), PASSWORD, null, stamp))
          .isInstanceOf(com.nilebank.common.ApiException.class);
    assertThat(mfa.settings(f.user.id()).enabled()).isTrue();
  }

  @Test
  void seedsCodesAndPasswordsAreNotLogged(
      org.springframework.boot.test.system.CapturedOutput output) {
    int start = output.getAll().length();
    var f = enrolled();
    mfa.verify(f.user.id(), version(f), f.codes.getFirst());
    assertThat(output.getAll().substring(start))
        .contains("MFA_ENABLED")
        .doesNotContain(f.secret, f.codes.getFirst(), PASSWORD, f.user.email());
  }
}

package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.nilebank.auth.recovery.RecoverySettings;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RecoverySettingsTest {
  MockEnvironment valid() {
    return new MockEnvironment()
        .withProperty("nilebank.auth.recovery.enabled", "true")
        .withProperty("nilebank.auth.recovery.smtp-host", "127.0.0.1")
        .withProperty("nilebank.auth.recovery.from", "demo@example.test")
        .withProperty("nilebank.auth.recovery.base-url", "http://localhost:8080");
  }

  @Test
  void disabledFeatureDoesNotNeedCredentials() {
    assertThatCode(() -> new RecoverySettings(new MockEnvironment())).doesNotThrowAnyException();
  }

  @Test
  void validLocalSinkIsAllowedWithoutTlsOrAuthentication() {
    assertThatCode(
            () ->
                new RecoverySettings(
                    valid().withProperty("nilebank.auth.recovery.smtp-starttls", "false")))
        .doesNotThrowAnyException();
  }

  @Test
  void invalidOriginAndFromAreRejectedWithoutValues() {
    for (String url :
        new String[] {
          "http://untrusted.example.test",
          "https://user:private@example.test",
          "https://example.test/path",
          "https://example.test/?private=token",
          "https://example.test/#private"
        })
      assertThatThrownBy(
              () ->
                  new RecoverySettings(
                      valid().withProperty("nilebank.auth.recovery.base-url", url)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining("private");
    assertThatThrownBy(
            () ->
                new RecoverySettings(
                    valid()
                        .withProperty(
                            "nilebank.auth.recovery.from",
                            "demo@example.test\r\nBcc: another@example.test")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void plaintextRemoteSmtpIsRejected() {
    assertThatThrownBy(
            () ->
                new RecoverySettings(
                    valid()
                        .withProperty("nilebank.auth.recovery.smtp-host", "smtp.example.test")
                        .withProperty("nilebank.auth.recovery.smtp-starttls", "false")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void productionRequiresCanonicalHttpsTlsAndAuthentication() {
    var env = valid();
    env.setActiveProfiles("prod");
    assertThatThrownBy(() -> new RecoverySettings(env)).isInstanceOf(IllegalStateException.class);
    env.withProperty("nilebank.auth.recovery.base-url", "https://demo.example.test")
        .withProperty("APP_PUBLIC_URL", "https://demo.example.test")
        .withProperty("nilebank.auth.recovery.smtp-user", "fictional-test-user")
        .withProperty("nilebank.auth.recovery.smtp-password", "fictional-test-only");
    assertThatCode(() -> new RecoverySettings(env)).doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                new RecoverySettings(
                    env.withProperty("nilebank.auth.recovery.smtp-starttls", "false")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void verificationOnlyStillRequiresSafeSmtpConfiguration() {
    var env =
        valid()
            .withProperty("nilebank.auth.recovery.enabled", "false")
            .withProperty("nilebank.auth.verification.enabled", "true");
    assertThatCode(() -> new com.nilebank.auth.recovery.RecoverySettings(env))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                new com.nilebank.auth.recovery.RecoverySettings(
                    env.withProperty("nilebank.auth.recovery.smtp-host", "")))
        .isInstanceOf(IllegalStateException.class);
  }
}

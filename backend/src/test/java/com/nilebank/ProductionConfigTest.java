package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.nilebank.config.ProductionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigTest {
  MockEnvironment valid() {
    return new MockEnvironment()
        .withProperty("spring.datasource.password", "Fictional-test-password-for-validation-only")
        .withProperty("spring.datasource.username", "nilebank")
        .withProperty("spring.datasource.url", "jdbc:postgresql://db/nilebank")
        .withProperty("server.servlet.session.cookie.secure", "true")
        .withProperty("APP_PUBLIC_URL", "https://demo.example.test")
        .withProperty("server.forward-headers-strategy", "native")
        .withProperty("server.tomcat.remoteip.internal-proxies", "127\\.0\\.0\\.1");
  }

  @Test
  void validSettingsDoNotNeedGoogleCredentials() {
    assertThatCode(() -> ProductionConfig.validate(valid())).doesNotThrowAnyException();
  }

  @Test
  void weakDatabasePasswordAndSuperuserAreRejectedWithoutPrintingValues() {
    assertThatThrownBy(
            () ->
                ProductionConfig.validate(
                    valid().withProperty("spring.datasource.password", "private-short")))
        .hasMessageNotContaining("private-short");
    assertThatThrownBy(
            () ->
                ProductionConfig.validate(
                    valid().withProperty("spring.datasource.username", "postgres")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void insecureCookiesAreRejected() {
    assertThatThrownBy(
            () ->
                ProductionConfig.validate(
                    valid().withProperty("server.servlet.session.cookie.secure", "false")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void publicOriginMustBeHttpsWithoutCredentialsPathsQueriesOrFragments() {
    for (String url :
        new String[] {
          "",
          "http://demo.example.test",
          "https://user:private@example.test",
          "https://example.test/path",
          "https://example.test/?code=private",
          "https://example.test/#private"
        })
      assertThatThrownBy(
              () -> ProductionConfig.validate(valid().withProperty("APP_PUBLIC_URL", url)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining("private");
  }

  @Test
  void proxyTrustMustBeExplicitAndSyntacticallyValid() {
    for (String regex : new String[] {"", ".*", ".+", "["})
      assertThatThrownBy(
              () ->
                  ProductionConfig.validate(
                      valid().withProperty("server.tomcat.remoteip.internal-proxies", regex)))
          .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                ProductionConfig.validate(
                    valid().withProperty("server.forward-headers-strategy", "framework")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void googleCallbackMustMatchPublicOriginAndCredentialsAreRequired() {
    var env = valid().withProperty("nilebank.auth.google.enabled", "true");
    assertThatThrownBy(
            () ->
                ProductionConfig.validate(
                    env.withProperty(
                        "nilebank.auth.google.redirect-uri",
                        "http://localhost:8080/login/oauth2/code/google")))
        .isInstanceOf(IllegalStateException.class);
    env.withProperty(
        "nilebank.auth.google.redirect-uri", "https://demo.example.test/login/oauth2/code/google");
    assertThatThrownBy(() -> ProductionConfig.validate(env))
        .isInstanceOf(IllegalStateException.class);
    env.withProperty("nilebank.auth.google.client-id", "fictional-client")
        .withProperty("nilebank.auth.google.client-secret", "fictional-test-only");
    assertThatCode(() -> ProductionConfig.validate(env)).doesNotThrowAnyException();
  }
}

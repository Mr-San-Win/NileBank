package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;

@ActiveProfiles("render")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "APP_PUBLIC_URL=https://demo.example.test",
      "nilebank.auth.google.enabled=false",
      "nilebank.finance.scheduler-enabled=false"
    })
class RenderHttpTest {
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withUsername("nilebankqa")
          .withPassword("Fictional-container-password-for-test-only");

  static {
    postgres.start();
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @LocalServerPort int port;
  @Autowired Environment environment;

  @Test
  void renderIncludesProductionValidationAndBoundedConnectionPool() {
    assertThat(environment.getActiveProfiles()).contains("render");
    assertThat(environment.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
    assertThatCode(() -> com.nilebank.config.ProductionConfig.validate(environment))
        .doesNotThrowAnyException();
    assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size"))
        .isEqualTo("3");
    assertThat(environment.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("0");
    assertThat(environment.getProperty("server.tomcat.remoteip.internal-proxies")).isEqualTo("^$");
  }

  @Test
  void edgeTerminatedHttpsGetsSecureCookiesWithoutTrustingSpoofedHeaders() throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf"))
                    .header("X-Forwarded-Proto", "http")
                    .header("X-Forwarded-For", "203.0.113.7")
                    .header("Forwarded", "for=203.0.113.8;proto=http")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Set-Cookie").orElseThrow())
        .contains("Secure", "HttpOnly", "SameSite=Lax");
    assertThat(response.headers().firstValue("Strict-Transport-Security")).isPresent();
  }

  @Test
  void frontendAndProtectedApiRemainSameOrigin() throws Exception {
    var client = HttpClient.newHttpClient();
    var home =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(home.statusCode()).isEqualTo(200);
    var me =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/me"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(me.statusCode()).isEqualTo(401);
  }
}

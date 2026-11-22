package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;

@ActiveProfiles("prod")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "APP_PUBLIC_URL=https://demo.example.test", "TRUSTED_PROXY_REGEX=127\\.0\\.0\\.1",
      "nilebank.auth.google.enabled=false", "nilebank.finance.scheduler-enabled=false"
    })
class ProductionHttpTest {
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

  @Test
  void secureSessionCookieAndHeadersWorkBehindExplicitTrustedProxy() throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf"))
                    .header("X-Forwarded-Proto", "https")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Set-Cookie").orElseThrow())
        .contains("Secure", "HttpOnly", "SameSite=Lax");
    assertThat(response.headers().firstValue("Strict-Transport-Security")).isPresent();
    assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow())
        .contains("default-src 'self'");
  }

  @Test
  void directHttpDoesNotAcquireSecureRequestStatusFromUnsupportedForwardedHeader()
      throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                    .header("Forwarded", "for=192.0.2.1;proto=https")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Strict-Transport-Security")).isEmpty();
  }

  @Test
  void untrustedSocketPeerCannotSpoofForwardedHttps() throws Exception {
    try (var socket = new java.net.Socket()) {
      socket.bind(new java.net.InetSocketAddress("127.0.0.2", 0));
      socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
      socket.setSoTimeout(5000);
      socket
          .getOutputStream()
          .write(
              ("GET /api/v1/health HTTP/1.1\r\n"
                   + "Host: localhost\r\n"
                   + "X-Forwarded-Proto: https\r\n"
                   + "X-Forwarded-For: 203.0.113.7\r\n"
                   + "Connection: close\r\n\r\n")
                  .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      String response =
          new String(
              socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(response).startsWith("HTTP/1.1 200");
      assertThat(response.toLowerCase(java.util.Locale.ROOT))
          .doesNotContain("strict-transport-security:");
    }
  }
}

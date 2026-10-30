package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpJourneyTest extends PostgresTest {
  @LocalServerPort int port;
  @Autowired ObjectMapper json;

  class BrowserClient {
    final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    JsonNode csrf;

    HttpResponse<String> request(
        String method, String path, Object body, String key, boolean protect) throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(java.time.Duration.ofSeconds(20));
      if (protect) request.header(csrf.get("headerName").asText(), csrf.get("token").asText());
      if (key != null) request.header("Idempotency-Key", key);
      if (body != null) request.header("Content-Type", "application/json");
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode get(String path) throws Exception {
      var response = request("GET", path, null, null, false);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      return json.readTree(response.body());
    }

    JsonNode post(String path, Object body, int expected, String key) throws Exception {
      var response = request("POST", path, body, key, true);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
      return response.body().isBlank() ? null : json.readTree(response.body());
    }

    void token() throws Exception {
      csrf = get("/api/v1/auth/csrf");
    }

    JsonNode registerLoginAndCreateAccount() throws Exception {
      token();
      String email = "http-" + UUID.randomUUID() + "@example.test";
      post(
          "/api/v1/auth/register",
          Map.of(
              "fullName", "HTTP Demo Student", "email", email, "password", "Demo-passphrase-2026"),
          201,
          null);
      var login =
          post(
              "/api/v1/auth/login",
              Map.of("email", email, "password", "Demo-passphrase-2026"),
              200,
              null);
      assertThat(login.has("passwordHash")).isFalse();
      token();
      assertThat(get("/api/v1/auth/me").get("email").asText()).isEqualTo(email);
      return post("/api/v1/accounts", null, 201, null);
    }
  }

  @Test
  void actualHttpSessionCsrfAndCompleteBankingJourney() throws Exception {
    var sender = new BrowserClient();
    var receiver = new BrowserClient();
    assertThat(sender.request("GET", "/api/v1/accounts/me", null, null, false).statusCode())
        .isEqualTo(401);
    assertThat(
            sender
                .request(
                    "POST",
                    "/api/v1/auth/login",
                    Map.of("email", "x@example.test", "password", "wrong"),
                    null,
                    false)
                .statusCode())
        .isEqualTo(403);
    var source = sender.registerLoginAndCreateAccount();
    var destination = receiver.registerLoginAndCreateAccount();
    String sourceId = source.get("id").asText();
    assertThat(source.get("balance").isTextual()).isTrue();
    assertThat(source.get("balance").asText()).isEqualTo("0.00");
    sender.post(
        "/api/v1/transactions/deposit",
        Map.of("accountId", sourceId, "amount", "100.50"),
        201,
        null);
    sender.post(
        "/api/v1/transactions/withdraw",
        Map.of("accountId", sourceId, "amount", "10.25"),
        201,
        null);
    var payload =
        Map.of(
            "sourceAccountId",
            sourceId,
            "recipientAccountNumber",
            destination.get("accountNumber").asText(),
            "amount",
            "20.25");
    var transfer = sender.post("/api/v1/transfers", payload, 200, "http-journey-key");
    assertThat(sender.post("/api/v1/transfers", payload, 200, "http-journey-key").get("id"))
        .isEqualTo(transfer.get("id"));
    assertThat(sender.get("/api/v1/accounts/me").get("balance").asText()).isEqualTo("70.00");
    assertThat(receiver.get("/api/v1/accounts/me").get("balance").asText()).isEqualTo("20.25");
    assertThat(sender.get("/api/v1/transactions").get("totalElements").asInt()).isEqualTo(3);
    assertThat(receiver.get("/api/v1/transactions").get("content").get(0).get("reference"))
        .isEqualTo(transfer.get("reference"));
    receiver.post(
        "/api/v1/transactions/withdraw", Map.of("accountId", sourceId, "amount", "1"), 404, null);
    sender.post("/api/v1/auth/logout", null, 204, null);
    assertThat(sender.request("GET", "/api/v1/auth/me", null, null, false).statusCode())
        .isEqualTo(401);
  }

  @Test
  void staticAssetsAndSecurityHeadersAreServedWithoutAuthentication() throws Exception {
    var browser = new BrowserClient();
    var response = browser.request("GET", "/", null, null, false);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Simulated money", "app.js");
    assertThat(response.headers().firstValue("Content-Security-Policy").orElse(""))
        .contains("default-src 'self'", "frame-ancestors 'none'");
    assertThat(response.headers().firstValue("X-Content-Type-Options").orElse(""))
        .isEqualTo("nosniff");
    assertThat(browser.request("GET", "/app.js", null, null, false).statusCode()).isEqualTo(200);
    var csrf = browser.request("GET", "/api/v1/auth/csrf", null, null, false);
    assertThat(csrf.headers().firstValue("Set-Cookie").orElse(""))
        .contains("HttpOnly", "SameSite=Lax");
  }
}

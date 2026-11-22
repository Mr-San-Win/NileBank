package com.nilebank;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.nilebank.account.AccountService;
import com.nilebank.auth.*;
import com.nilebank.user.UserRepository;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;

/**
 * Exercises actual redirects, callback filter, code exchange, JWKS and signed ID-token validation.
 * Only Google's network endpoints are replaced. No oidcLogin() authentication shortcut is used.
 */
@SpringBootTest(properties = "nilebank.auth.google.enabled=true")
class GoogleOidcTest extends PostgresTest {
  @org.springframework.test.context.DynamicPropertySource
  static void mfaKeys(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.add("nilebank.auth.mfa.enabled", () -> true);
    r.add("nilebank.auth.mfa.active-key-id", () -> "test");
    r.add("nilebank.auth.mfa.keys-json", () -> "{\"test\":\"" + MfaCryptoTest.TEST_KEY + "\"}");
  }

  @Autowired com.nilebank.auth.mfa.MfaService mfa;
  @Autowired AccountSecurityService accountSecurity;

  static final ObjectMapper JSON = new ObjectMapper();
  static final String ISSUER = "https://accounts.google.com";
  static final String CLIENT = "nilebank-test-client";
  static final RSAKey KEY;
  static final HttpServer SERVER;
  static final Map<String, Fixture> CODES = new ConcurrentHashMap<>();
  static final Map<String, Fixture> TOKENS = new ConcurrentHashMap<>();
  static final AtomicInteger EXCHANGES = new AtomicInteger();

  static {
    try {
      KEY = new RSAKeyGenerator(2048).keyID("local-test-signing-key").generate();
      SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      SERVER.createContext(
          "/jwks",
          exchange -> {
            byte[] bytes = JSON.writeValueAsBytes(new JWKSet(KEY.toPublicJWK()).toJSONObject());
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      SERVER.createContext(
          "/token",
          exchange -> {
            EXCHANGES.incrementAndGet();
            var params =
                params(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var fixture = CODES.remove(params.get("code"));
            try {
              String verifier = params.get("code_verifier");
              boolean valid =
                  fixture != null
                      && verifier != null
                      && challenge(verifier).equals(fixture.challenge);
              if (!valid) {
                byte[] body = JSON.writeValueAsBytes(Map.of("error", "invalid_grant"));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, body.length);
                exchange.getResponseBody().write(body);
              } else {
                String token = "test-access-" + UUID.randomUUID();
                TOKENS.put(token, fixture);
                byte[] body =
                    JSON.writeValueAsBytes(
                        Map.of(
                            "access_token",
                            token,
                            "token_type",
                            "Bearer",
                            "expires_in",
                            300,
                            "scope",
                            "openid email profile",
                            "id_token",
                            signedToken(fixture)));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
              }
            } catch (Exception e) {
              throw new java.io.IOException("Local OIDC fixture failure", e);
            } finally {
              exchange.close();
            }
          });
      SERVER.createContext(
          "/userinfo",
          exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            var fixture = TOKENS.get(auth == null ? "" : auth.replace("Bearer ", ""));
            var info = new HashMap<String, Object>();
            if (fixture != null) {
              info.put("sub", fixture.subject);
              info.put("email", fixture.email);
              info.put("email_verified", !fixture.mode.equals("unverified"));
              info.put("name", "OIDC Demo Student");
            }
            byte[] bytes = JSON.writeValueAsBytes(info);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(fixture == null ? 401 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      SERVER.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static class Fixture {
    final String subject;
    final String email;
    final String nonce;
    String challenge;
    String mode = "valid";

    Fixture(String subject, String email, String nonce, String challenge) {
      this.subject = subject;
      this.email = email;
      this.nonce = nonce;
      this.challenge = challenge;
    }
  }

  record Flow(
      MockHttpSession session,
      String state,
      String code,
      Fixture fixture,
      String originalSessionId,
      JsonNode oldCsrf) {}

  static String base() {
    return "http://127.0.0.1:" + SERVER.getAddress().getPort();
  }

  static String challenge(String verifier) throws Exception {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
  }

  static Map<String, String> params(String query) {
    var result = new HashMap<String, String>();
    if (query == null) return result;
    for (String pair : query.split("&")) {
      var parts = pair.split("=", 2);
      result.put(
          URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
    }
    return result;
  }

  static String signedToken(Fixture f) throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(f.mode.equals("issuer") ? "https://attacker.example" : ISSUER)
            .audience(f.mode.equals("audience") ? "other-client" : CLIENT)
            .issueTime(Date.from(now.minusSeconds(f.mode.equals("expired") ? 900 : 0)))
            .expirationTime(Date.from(now.plusSeconds(f.mode.equals("expired") ? -600 : 300)))
            .claim("nonce", f.mode.equals("nonce") ? "wrong-nonce" : f.nonce)
            .claim("email", f.email)
            .claim("email_verified", !f.mode.equals("unverified"))
            .claim("name", "OIDC Demo Student");
    if (f.mode.equals("missingNonce")) claims.claim("nonce", null);
    if (f.mode.equals("authorizedParty"))
      claims.audience(List.of(CLIENT, "other-client")).claim("azp", "other-client");
    if (!f.mode.equals("subject")) claims.subject(f.subject);
    if (f.mode.equals("unsigned")) return new PlainJWT(claims.build()).serialize();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
            claims.build());
    jwt.sign(
        new RSASSASigner(f.mode.equals("signature") ? new RSAKeyGenerator(2048).generate() : KEY));
    return jwt.serialize();
  }

  @Autowired MockMvc mvc;
  @Autowired UserRepository users;
  @Autowired AuthService auth;
  @Autowired AccountService accounts;
  @Autowired ExternalIdentityRepository identities;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  ExternalIdentityService identityService;

  @MockitoBean ClientRegistrationRepository googleRegistrations;

  static final AtomicInteger PEERS = new AtomicInteger();
  String peer;

  @BeforeEach
  void registration() {
    peer = "192.0.2." + PEERS.incrementAndGet();
    var registration =
        ClientRegistration.withRegistrationId("google")
            .clientId(CLIENT)
            .clientSecret("fictional-test-client-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/login/oauth2/code/google")
            .scope("openid", "email", "profile")
            .authorizationUri(base() + "/authorize")
            .tokenUri(base() + "/token")
            .jwkSetUri(base() + "/jwks")
            .issuerUri(ISSUER)
            .userInfoUri(base() + "/userinfo")
            .userNameAttributeName("sub")
            .clientSettings(
                ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
            .build();
    when(googleRegistrations.findByRegistrationId("google")).thenReturn(registration);
  }

  Flow start(String subject, String email) throws Exception {
    return startInSession(subject, email, new MockHttpSession());
  }

  Flow startInSession(String subject, String email, MockHttpSession session) throws Exception {
    var csrfResponse =
        mvc.perform(get("/api/v1/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn();
    String original = session.getId();
    var oldCsrf = JSON.readTree(csrfResponse.getResponse().getContentAsString());
    var result =
        mvc.perform(
                get("/oauth2/authorization/google")
                    .with(
                        request -> {
                          request.setRemoteAddr(peer);
                          return request;
                        })
                    .session(session)
                    .param("next", "https://attacker.example"))
            .andExpect(status().isFound())
            .andReturn();
    var params = params(URI.create(result.getResponse().getRedirectedUrl()).getRawQuery());
    assertThat(params.get("response_type")).isEqualTo("code");
    assertThat(params.get("code_challenge_method")).isEqualTo("S256");
    assertThat(params.get("code_challenge")).isNotBlank();
    assertThat(params.get("nonce")).isNotBlank();
    assertThat(params.get("state")).isNotBlank();
    var fixture = new Fixture(subject, email, params.get("nonce"), params.get("code_challenge"));
    String code = UUID.randomUUID().toString();
    CODES.put(code, fixture);
    return new Flow(session, params.get("state"), code, fixture, original, oldCsrf);
  }

  Flow start() throws Exception {
    return start(UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test");
  }

  ResultActions callback(Flow flow) throws Exception {
    return mvc.perform(
        get("/login/oauth2/code/google")
            .with(
                request -> {
                  request.setRemoteAddr(peer);
                  return request;
                })
            .session(flow.session)
            .param("state", flow.state)
            .param("code", flow.code));
  }

  void rejected(Flow flow) throws Exception {
    callback(flow).andExpect(status().isFound()).andExpect(redirectedUrl("/?authError=google"));
    mvc.perform(get("/api/v1/auth/me").session(flow.session)).andExpect(status().isUnauthorized());
    assertThat(identities.findByIssuerAndProviderSubject(ISSUER, flow.fixture.subject)).isEmpty();
  }

  @Test
  void realSignedGoogleCallbackCannotBypassEnrolledSecondFactor() throws Exception {
    String password = "Fictional-MFA-Google-password-2026";
    var user = auth.register("Google MFA Demo", UUID.randomUUID() + "@example.test", password);
    var enrollment = mfa.begin(user.id(), password);
    var codes =
        mfa.confirm(
            user.id(),
            password,
            com.nilebank.auth.mfa.Totp.code(
                com.nilebank.auth.mfa.Totp.decode(enrollment.secret()),
                Instant.now().getEpochSecond() / 30));
    String subject = UUID.randomUUID().toString();
    accountSecurity.link(
        accountSecurity.prepareLink(user.id(), password),
        ValidatedExternalIdentity.google(ISSUER, subject, user.email(), true, "Provider Name"));
    var flow = start(subject, user.email());
    callback(flow).andExpect(status().isFound()).andExpect(redirectedUrl("/"));
    mvc.perform(get("/api/v1/auth/me").session(flow.session)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/accounts/me").session(flow.session)).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/auth/mfa/challenge")
                .session(flow.session)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("code", codes.getFirst()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(user.id().toString()));
    mvc.perform(get("/api/v1/auth/me").session(flow.session)).andExpect(status().isOk());
  }

  @Test
  void realCallbackProvisionsUuidRotatesSessionAndSupportsBankingCsrfAndLogout() throws Exception {
    var flow = start();
    callback(flow).andExpect(status().isFound()).andExpect(redirectedUrl("/"));
    assertThat(flow.session.getId()).isNotEqualTo(flow.originalSessionId);
    var me =
        mvc.perform(get("/api/v1/auth/me").session(flow.session))
            .andExpect(status().isOk())
            .andReturn();
    var profile = JSON.readTree(me.getResponse().getContentAsString());
    UUID internal = UUID.fromString(profile.get("id").asText());
    assertThat(profile.has("idToken")).isFalse();
    assertThat(profile.has("accessToken")).isFalse();
    assertThat(users.findById(internal).orElseThrow().getPasswordHash()).isNull();
    mvc.perform(post("/api/v1/accounts").session(flow.session)).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/accounts")
                .session(flow.session)
                .header(
                    flow.oldCsrf.get("headerName").asText(), flow.oldCsrf.get("token").asText()))
        .andExpect(status().isForbidden());
    var token =
        JSON.readTree(
            mvc.perform(get("/api/v1/auth/csrf").session(flow.session))
                .andReturn()
                .getResponse()
                .getContentAsString());
    String header = token.get("headerName").asText(), value = token.get("token").asText();
    var created =
        mvc.perform(post("/api/v1/accounts").session(flow.session).header(header, value))
            .andExpect(status().isCreated())
            .andReturn();
    UUID account =
        UUID.fromString(
            JSON.readTree(created.getResponse().getContentAsString()).get("id").asText());
    mvc.perform(
            post("/api/v1/transactions/deposit")
                .session(flow.session)
                .header(header, value)
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("accountId", account, "amount", "35.00"))))
        .andExpect(status().isCreated());
    var stranger =
        auth.register("Other Demo", UUID.randomUUID() + "@example.test", "Example-passphrase-21");
    var otherAccount = accounts.create(stranger.id());
    mvc.perform(
            post("/api/v1/transactions/withdraw")
                .session(flow.session)
                .header(header, value)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        Map.of("accountId", otherAccount.id(), "amount", "1.00"))))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/auth/logout").session(flow.session).header(header, value))
        .andExpect(status().isNoContent());
    assertThat(flow.session.isInvalid()).isTrue();
    mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void returningSubjectKeepsInternalUuidAndOriginalProfile() throws Exception {
    String subject = UUID.randomUUID().toString();
    var first = start(subject, UUID.randomUUID() + "@example.test");
    callback(first).andExpect(redirectedUrl("/"));
    var original = users.findByEmail(first.fixture.email).orElseThrow();
    var second = start(subject, UUID.randomUUID() + "@example.test");
    callback(second).andExpect(redirectedUrl("/"));
    mvc.perform(get("/api/v1/auth/me").session(second.session))
        .andExpect(jsonPath("$.id").value(original.getId().toString()))
        .andExpect(jsonPath("$.email").value(original.getEmail()));
  }

  @Test
  void matchingPasswordEmailCannotAuthenticateOrLink() throws Exception {
    String email = UUID.randomUUID() + "@example.test";
    var password = auth.register("Password Demo", email, "Example-passphrase-21");
    var flow = start(UUID.randomUUID().toString(), email);
    rejected(flow);
    assertThat(users.findById(password.id()).orElseThrow().getPasswordHash()).isNotNull();
  }

  @Test
  void rejectsWrongStateWithoutExchangingCode() throws Exception {
    var flow = start();
    int before = EXCHANGES.get();
    mvc.perform(
            get("/login/oauth2/code/google")
                .with(
                    request -> {
                      request.setRemoteAddr(peer);
                      return request;
                    })
                .session(flow.session)
                .param("state", "wrong")
                .param("code", flow.code))
        .andExpect(redirectedUrl("/?authError=google"));
    assertThat(EXCHANGES.get()).isEqualTo(before);
    assertThat(users.findByEmail(flow.fixture.email)).isEmpty();
  }

  @Test
  void rejectsStateFromAnotherSessionAndMissingAuthorizationRequest() throws Exception {
    var flow = start();
    int before = EXCHANGES.get();
    mvc.perform(
            get("/login/oauth2/code/google")
                .session(new MockHttpSession())
                .param("state", flow.state)
                .param("code", flow.code))
        .andExpect(redirectedUrl("/?authError=google"));
    assertThat(EXCHANGES.get()).isEqualTo(before);
  }

  @Test
  void rejectsNonceMismatch() throws Exception {
    var flow = start();
    flow.fixture.mode = "nonce";
    rejected(flow);
  }

  @Test
  void rejectsForgedExpiredWrongIssuerAudienceUnsignedAndMissingSubjectTokens() throws Exception {
    for (String mode :
        List.of(
            "signature",
            "expired",
            "issuer",
            "audience",
            "unsigned",
            "subject",
            "missingNonce",
            "authorizedParty")) {
      var flow = start();
      flow.fixture.mode = mode;
      rejected(flow);
    }
  }

  @Test
  void unverifiedEmailCannotProvision() throws Exception {
    var flow = start();
    flow.fixture.mode = "unverified";
    rejected(flow);
  }

  @Test
  void wrongPkceProofCannotExchangeCode() throws Exception {
    var flow = start();
    flow.fixture.challenge = "not-the-issued-challenge";
    rejected(flow);
  }

  @Test
  void callbackReplayCannotExchangeTheCodeTwice() throws Exception {
    var flow = start();
    callback(flow).andExpect(redirectedUrl("/"));
    int before = EXCHANGES.get();
    callback(flow).andExpect(redirectedUrl("/?authError=google"));
    assertThat(EXCHANGES.get()).isEqualTo(before);
    assertThat(identities.findByIssuerAndProviderSubject(ISSUER, flow.fixture.subject)).isPresent();
  }

  @Test
  void consentDenialUsesSafeFixedFailureDestination() throws Exception {
    var flow = start();
    mvc.perform(
            get("/login/oauth2/code/google")
                .with(
                    request -> {
                      request.setRemoteAddr(peer);
                      return request;
                    })
                .session(flow.session)
                .param("state", flow.state)
                .param("error", "access_denied")
                .param("error_description", "private provider detail"))
        .andExpect(redirectedUrl("/?authError=google"));
    assertThat(users.findByEmail(flow.fixture.email)).isEmpty();
  }

  @Test
  void enabledOptionsContainNoCredentialsAndApisRetainJson401() throws Exception {
    mvc.perform(get("/api/v1/auth/options"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"googleEnabled\":true}"));
    mvc.perform(get("/api/v1/accounts/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("Location"));
  }

  @Test
  void databaseOutageUsesGenericFailureWithoutAuthenticating() throws Exception {
    var flow = start();
    doThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "private database fixture detail"))
        .when(identityService)
        .resolveOrProvision(org.mockito.ArgumentMatchers.any());
    rejected(flow);
  }

  MockHttpSession passwordSession(AuthService.UserView user) throws Exception {
    var result =
        mvc.perform(
                post("/api/v1/auth/login")
                    .session(new MockHttpSession())
                    .with(
                        org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors.csrf())
                    .contentType("application/json")
                    .content(
                        JSON.writeValueAsString(
                            Map.of("email", user.email(), "password", "Example-passphrase-21"))))
            .andExpect(status().isOk())
            .andReturn();
    return (MockHttpSession) result.getRequest().getSession(false);
  }

  void prepareLink(MockHttpSession session, String proof) throws Exception {
    mvc.perform(
            post("/api/v1/auth/security/google/link")
                .session(session)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("currentPassword", proof))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.redirect").value("/oauth2/authorization/google"));
  }

  @Test
  void explicitLinkThroughValidatedCallbackKeepsOwnerAndRevokesOtherSession() throws Exception {
    var owner =
        auth.register("Link Owner", UUID.randomUUID() + "@example.test", "Example-passphrase-21");
    var different =
        auth.register(
            "Different Profile", UUID.randomUUID() + "@example.test", "Example-passphrase-21");
    var session = passwordSession(owner);
    var otherSession = passwordSession(owner);
    prepareLink(session, "Example-passphrase-21");
    var flow = startInSession(UUID.randomUUID().toString(), different.email(), session);
    callback(flow).andExpect(redirectedUrl("/"));
    assertThat(session.getAttribute(AccountSecurityController.PENDING_LINK)).isNull();
    mvc.perform(get("/api/v1/auth/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(owner.id().toString()))
        .andExpect(jsonPath("$.email").value(owner.email()));
    mvc.perform(get("/api/v1/auth/security").session(session))
        .andExpect(jsonPath("$.google").value(true));
    mvc.perform(get("/api/v1/auth/me").session(otherSession)).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/security/google/unlink")
                .session(session)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json")
                .content("{\"currentPassword\":\"Example-passphrase-21\"}"))
        .andExpect(status().isNoContent());
    assertThat(session.isInvalid()).isTrue();
    assertThat(identities.findByIssuerAndProviderSubject(ISSUER, flow.fixture.subject)).isEmpty();
  }

  @Test
  void linkingRequiresAuthenticationCsrfAndFreshPassword() throws Exception {
    mvc.perform(
            post("/api/v1/auth/security/google/link")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json")
                .content("{\"currentPassword\":\"Example-passphrase-21\"}"))
        .andExpect(status().isUnauthorized());
    var owner =
        auth.register("Link Owner", UUID.randomUUID() + "@example.test", "Example-passphrase-21");
    var session = passwordSession(owner);
    mvc.perform(
            post("/api/v1/auth/security/google/link")
                .session(session)
                .contentType("application/json")
                .content("{\"currentPassword\":\"Example-passphrase-21\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/auth/security/google/link")
                .session(session)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json")
                .content("{\"currentPassword\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
    assertThat(session.getAttribute(AccountSecurityController.PENDING_LINK)).isNull();
  }

  @Test
  void cannotLinkIdentityAlreadyOwnedByAnotherGoogleUser() throws Exception {
    var existing = start();
    callback(existing).andExpect(redirectedUrl("/"));
    var owner =
        auth.register("Link Owner", UUID.randomUUID() + "@example.test", "Example-passphrase-21");
    var session = passwordSession(owner);
    prepareLink(session, "Example-passphrase-21");
    var attempt = startInSession(existing.fixture.subject, existing.fixture.email, session);
    callback(attempt).andExpect(redirectedUrl("/?authError=google"));
    assertThat(
            identities
                .findByIssuerAndProviderSubject(ISSUER, existing.fixture.subject)
                .orElseThrow()
                .getUser()
                .getId())
        .isNotEqualTo(owner.id());
  }

  @AfterAll
  static void stopProvider() {
    SERVER.stop(0);
    CODES.clear();
    TOKENS.clear();
  }
}

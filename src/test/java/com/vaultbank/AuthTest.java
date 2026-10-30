package com.vaultbank;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.vaultbank.user.UserRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;

class AuthTest extends PostgresTest {
  @Test
  void oversizedUtf8PasswordIsRejectedWithoutInternalError() throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content(body(email(), "ก".repeat(30))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Invalid email or password"));
  }

  @Test
  void unsupportedMethodAndMediaKeepTheirHttpStatus() throws Exception {
    mvc.perform(get("/api/v1/auth/login")).andExpect(status().isMethodNotAllowed());
    mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("text/plain").content("hello"))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;

  String email() {
    return "student-" + UUID.randomUUID() + "@example.test";
  }

  String body(String email, String password) throws Exception {
    return json.writeValueAsString(Map.of("email", email, "password", password));
  }

  void register(String email) throws Exception {
    mvc.perform(
            post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "fullName",
                            "Demo Student",
                            "email",
                            email,
                            "password",
                            "Example-passphrase-21"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.passwordHash").doesNotExist());
  }

  @Test
  void registrationNormalizesAndHashesPassword() throws Exception {
    String email = email().toUpperCase(Locale.ROOT);
    register(email);
    var user = users.findByEmail(email.toLowerCase(Locale.ROOT)).orElseThrow();
    assertThat(user.getPasswordHash()).startsWith("$2").doesNotContain("Example-passphrase");
  }

  @Test
  void duplicateRegistrationIsRejected() throws Exception {
    String email = email();
    register(email);
    mvc.perform(
            post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "fullName",
                            "Demo",
                            "email",
                            email,
                            "password",
                            "Example-passphrase-21"))))
        .andExpect(status().isConflict());
  }

  @Test
  void invalidCredentialsAreGeneric() throws Exception {
    String email = email();
    register(email);
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content(body(email, "wrong-password")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Invalid email or password"));
  }

  @Test
  void anonymousAccessRejected() throws Exception {
    mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void csrfRequiredEvenOnLoginAndRegistration() throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(body(email(), "Example-passphrase-21")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void actualDeliveredCsrfTokenWorksAndRotatesAtLoginAndLogoutInvalidatesSession()
      throws Exception {
    String email = email();
    register(email);
    var tokenResponse =
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
    MockHttpSession session = (MockHttpSession) tokenResponse.getRequest().getSession();
    JsonNode token = json.readTree(tokenResponse.getResponse().getContentAsString());
    mvc.perform(
            post("/api/v1/auth/login")
                .session(session)
                .header(token.get("headerName").asText(), token.get("token").asText())
                .contentType("application/json")
                .content(body(email, "Example-passphrase-21")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/auth/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(email));
    mvc.perform(
            post("/api/v1/auth/logout")
                .session(session)
                .header(token.get("headerName").asText(), token.get("token").asText()))
        .andExpect(status().isForbidden());
    var fresh = mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn();
    JsonNode next = json.readTree(fresh.getResponse().getContentAsString());
    mvc.perform(
            post("/api/v1/auth/logout")
                .session(session)
                .header(next.get("headerName").asText(), next.get("token").asText()))
        .andExpect(status().isNoContent());
    assertThat(session.isInvalid()).isTrue();
    mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void invalidRegistrationIsUniform() throws Exception {
    mvc.perform(
            post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"fullName\":\"\",\"email\":\"invalid\",\"password\":\"short\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.email").exists());
  }
}

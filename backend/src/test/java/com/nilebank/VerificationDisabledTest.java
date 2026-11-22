package com.nilebank;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nilebank.auth.AuthService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class VerificationDisabledTest extends PostgresTest {
  @Autowired AuthService auth;
  @Autowired MockMvc mvc;

  @Test
  void defaultOffDoesNotVerifyOrRestrictExistingUsers() throws Exception {
    var a =
        auth.register(
            "Default Demo", UUID.randomUUID() + "@example.test", "Fictional-password-2026");
    mvc.perform(get("/api/v1/auth/security/email").with(user(a.id().toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.verified").value(false));
    mvc.perform(
            post("/api/v1/auth/security/email/request").with(user(a.id().toString())).with(csrf()))
        .andExpect(status().isAccepted());
    mvc.perform(
            post("/api/v1/auth/email/confirm")
                .with(csrf())
                .contentType("application/json")
                .content("{\"token\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/auth/me").with(user(a.id().toString()))).andExpect(status().isOk());
  }
}

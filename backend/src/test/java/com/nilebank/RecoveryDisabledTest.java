package com.nilebank;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class RecoveryDisabledTest extends PostgresTest {
  @Autowired MockMvc mvc;

  @Test
  void unavailableRecoveryIsNotAdvertisedButRequestsRemainGeneric() throws Exception {
    mvc.perform(get("/api/v1/auth/options"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recoveryEnabled").value(false));
    mvc.perform(
            post("/api/v1/auth/recovery/request")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"fictional@example.test\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.token").doesNotExist());
  }

  @Test
  void disabledResetCannotCreatePassword() throws Exception {
    mvc.perform(
            post("/api/v1/auth/recovery/reset")
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"token\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"password\":\"Fictional-password-2026\"}"))
        .andExpect(status().isBadRequest());
  }
}

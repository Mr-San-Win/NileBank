package com.nilebank.auth;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthOptionsController {
  private final boolean googleEnabled;
  private final com.nilebank.auth.recovery.PasswordRecoveryService recovery;

  public AuthOptionsController(
      @Value("${nilebank.auth.google.enabled:false}") boolean googleEnabled,
      com.nilebank.auth.recovery.PasswordRecoveryService recovery) {
    this.googleEnabled = googleEnabled;
    this.recovery = recovery;
  }

  @GetMapping("/api/v1/auth/options")
  public Map<String, Boolean> options() {
    return Map.of("googleEnabled", googleEnabled, "recoveryEnabled", recovery.available());
  }
}

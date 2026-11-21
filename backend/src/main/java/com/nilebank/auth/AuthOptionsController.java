package com.nilebank.auth;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthOptionsController {
  private final boolean googleEnabled;

  public AuthOptionsController(
      @Value("${nilebank.auth.google.enabled:false}") boolean googleEnabled) {
    this.googleEnabled = googleEnabled;
  }

  @GetMapping("/api/v1/auth/options")
  public Map<String, Boolean> options() {
    return Map.of("googleEnabled", googleEnabled);
  }
}

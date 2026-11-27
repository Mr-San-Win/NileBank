package com.nilebank.auth.recovery;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/recovery")
public class PasswordRecoveryController {
  private final PasswordRecoveryService recovery;

  public PasswordRecoveryController(PasswordRecoveryService recovery) {
    this.recovery = recovery;
  }

  public record Request(@NotBlank @Email @Size(max = 254) String email) {}

  public record Reset(
      @NotBlank @Size(max = 100) String token,
      @NotBlank @Size(min = 12, max = 72) String password) {}

  @PostMapping("/request")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, String> request(@Valid @RequestBody Request request) {
    recovery.request(request.email());
    return Map.of(
        "message",
        "If password recovery is available for that profile, an email will be sent. Check your"
            + " inbox or try again later.");
  }

  @PostMapping("/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset(@Valid @RequestBody Reset body, HttpServletRequest request) {
    recovery.reset(body.token(), body.password());
    if (request.getSession(false) != null) request.getSession(false).invalidate();
    SecurityContextHolder.clearContext();
  }
}

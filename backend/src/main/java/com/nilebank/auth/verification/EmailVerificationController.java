package com.nilebank.auth.verification;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
public class EmailVerificationController {
  private final EmailVerificationService verification;

  public EmailVerificationController(EmailVerificationService verification) {
    this.verification = verification;
  }

  public record Confirmation(@NotBlank @Size(max = 100) String token) {}

  @GetMapping("/api/v1/auth/security/email")
  public EmailVerificationService.Status status(Principal p) {
    return verification.status(UUID.fromString(p.getName()));
  }

  @PostMapping("/api/v1/auth/security/email/request")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, String> request(Principal p) {
    verification.request(UUID.fromString(p.getName()));
    return Map.of(
        "message",
        "If confirmation is needed and delivery is available, an email will be sent. No account"
            + " access changes until you sign in normally.");
  }

  @PostMapping("/api/v1/auth/email/confirm")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void confirm(@Valid @RequestBody Confirmation body, HttpServletRequest request) {
    verification.confirm(body.token());
    if (request.getSession(false) != null) request.getSession(false).invalidate();
    SecurityContextHolder.clearContext();
  }
}

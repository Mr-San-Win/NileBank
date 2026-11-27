package com.nilebank.auth;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/security")
public class AccountSecurityController {
  public static final String PENDING_LINK = "nilebank.pendingGoogleLink";
  private final AccountSecurityService service;
  private final boolean googleEnabled;

  public AccountSecurityController(
      AccountSecurityService service,
      @Value("${nilebank.auth.google.enabled:false}") boolean googleEnabled) {
    this.service = service;
    this.googleEnabled = googleEnabled;
  }

  public record Proof(@NotBlank @Size(max = 72) String currentPassword) {}

  public record PasswordChange(
      @NotBlank @Size(max = 72) String currentPassword,
      @NotBlank @Size(min = 12, max = 72) String newPassword) {}

  @GetMapping
  public AccountSecurityService.Methods methods(Principal principal) {
    return service.methods(UUID.fromString(principal.getName()));
  }

  @PostMapping("/google/link")
  public Map<String, String> link(
      Principal principal, @Valid @RequestBody Proof proof, HttpServletRequest request) {
    if (!googleEnabled)
      throw new com.nilebank.common.ApiException(
          HttpStatus.CONFLICT, "Google sign-in is not enabled");
    var intent = service.prepareLink(UUID.fromString(principal.getName()), proof.currentPassword());
    request.getSession().removeAttribute(GoogleReauthentication.SESSION_INTENT);
    request.getSession().setAttribute(PENDING_LINK, intent);
    return Map.of("redirect", "/oauth2/authorization/google");
  }

  @PostMapping("/google/unlink")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unlink(
      Principal principal,
      @Valid @RequestBody Proof proof,
      HttpServletRequest request,
      HttpServletResponse response) {
    service.unlink(UUID.fromString(principal.getName()), proof.currentPassword());
    close(request, response);
  }

  @PostMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void password(
      Principal principal,
      @Valid @RequestBody PasswordChange change,
      HttpServletRequest request,
      HttpServletResponse response) {
    service.changePassword(
        UUID.fromString(principal.getName()), change.currentPassword(), change.newPassword());
    close(request, response);
  }

  private void close(HttpServletRequest request, HttpServletResponse response) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    new SecurityContextLogoutHandler().logout(request, response, auth);
    new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, auth);
  }
}

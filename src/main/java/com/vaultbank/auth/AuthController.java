package com.vaultbank.auth;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService service;
  private final AuthenticationManager manager;
  private final SessionAuthenticationStrategy sessions;
  private final SecurityContextRepository contexts;

  public AuthController(
      AuthService service,
      AuthenticationManager manager,
      SessionAuthenticationStrategy sessions,
      SecurityContextRepository contexts) {
    this.service = service;
    this.manager = manager;
    this.sessions = sessions;
    this.contexts = contexts;
  }

  public record Register(
      @NotBlank @Size(max = 100) String fullName,
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 72) String password) {}

  public record Login(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthService.UserView register(@Valid @RequestBody Register r) {
    return service.register(r.fullName(), r.email(), r.password());
  }

  @PostMapping("/login")
  public AuthService.UserView login(
      @Valid @RequestBody Login r, HttpServletRequest request, HttpServletResponse response) {
    if (r.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
      throw new org.springframework.security.authentication.BadCredentialsException(
          "Invalid credentials");
    }
    var authentication =
        manager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(r.email(), r.password()));
    sessions.onAuthentication(authentication, request, response);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    return service.me(UUID.fromString(authentication.getName()));
  }

  @GetMapping("/me")
  public AuthService.UserView me(Principal principal) {
    return service.me(UUID.fromString(principal.getName()));
  }
}

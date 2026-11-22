package com.nilebank.auth.mfa;

import com.nilebank.auth.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

@RestController
public class MfaController {
  private final MfaService mfa;
  private final AuthService users;
  private final SessionAuthenticationStrategy sessions;
  private final SecurityContextRepository contexts;

  public MfaController(
      MfaService mfa,
      AuthService users,
      SessionAuthenticationStrategy sessions,
      SecurityContextRepository contexts) {
    this.mfa = mfa;
    this.users = users;
    this.sessions = sessions;
    this.contexts = contexts;
  }

  public record Proof(@Size(max = 72) String currentPassword, @Size(max = 64) String code) {}

  public record Code(@NotBlank @Size(max = 64) String code) {}

  @GetMapping("/api/v1/auth/mfa/status")
  public Map<String, Object> status() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    boolean required =
        MfaService.pending(auth) && MfaService.pendingState(auth).expires().isAfter(Instant.now());
    return Map.of("required", required);
  }

  @PostMapping("/api/v1/auth/mfa/challenge")
  public AuthService.UserView challenge(
      @Valid @RequestBody Code body, HttpServletRequest request, HttpServletResponse response) {
    var pendingAuth = SecurityContextHolder.getContext().getAuthentication();
    var session = request.getSession(false);
    if (!MfaService.pending(pendingAuth) || session == null) throw MfaService.invalid();
    var pending = MfaService.pendingState(pendingAuth);
    if (!pending.expires().isAfter(Instant.now()) || pending.attempts().incrementAndGet() > 5) {
      invalidate(request);
      throw MfaService.invalid();
    }
    var original = pending.original();
    UUID owner = UUID.fromString(original.getName());
    mfa.verify(owner, MfaService.version(original), body.code());
    synchronized (session) {
      try {
        session.getAttributeNames();
      } catch (IllegalStateException expired) {
        throw MfaService.invalid();
      }
      var saved =
          session.getAttribute(
              org.springframework.security.web.context.HttpSessionSecurityContextRepository
                  .SPRING_SECURITY_CONTEXT_KEY);
      if (!(saved instanceof org.springframework.security.core.context.SecurityContext live)
          || live.getAuthentication() != pendingAuth) throw MfaService.invalid();
      sessions.onAuthentication(original, request, response);
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(original);
      SecurityContextHolder.setContext(context);
      contexts.saveContext(context, request, response);
      request
          .getSession()
          .setAttribute(
              MfaService.VERIFIED,
              new MfaService.Verified(owner, MfaService.version(original), Instant.now()));
    }
    SecurityEvents.record(SecurityEvents.Event.LOGIN, owner);
    return users.me(owner);
  }

  @GetMapping("/api/v1/auth/security/mfa")
  public MfaService.Settings settings(Principal p) {
    return mfa.settings(UUID.fromString(p.getName()), googleProof());
  }

  @PostMapping("/api/v1/auth/security/mfa/enroll")
  public MfaService.Enrollment begin(Principal p, @Valid @RequestBody Proof body) {
    return mfa.begin(UUID.fromString(p.getName()), body.currentPassword(), googleProof());
  }

  @PostMapping("/api/v1/auth/security/mfa/confirm")
  public Map<String, List<String>> confirm(
      Principal p, @Valid @RequestBody Proof body, HttpServletRequest request) {
    var codes =
        mfa.confirm(
            UUID.fromString(p.getName()), body.currentPassword(), body.code(), googleProof());
    invalidate(request);
    return Map.of("recoveryCodes", codes);
  }

  @PostMapping("/api/v1/auth/security/mfa/disable")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void disable(Principal p, @Valid @RequestBody Proof body, HttpServletRequest request) {
    mfa.disable(
        UUID.fromString(p.getName()),
        body.currentPassword(),
        body.code(),
        stamp(request),
        googleProof());
    invalidate(request);
  }

  @PostMapping("/api/v1/auth/security/mfa/recovery-codes")
  public Map<String, List<String>> regenerate(
      Principal p, @Valid @RequestBody Proof body, HttpServletRequest request) {
    var codes =
        mfa.regenerate(
            UUID.fromString(p.getName()),
            body.currentPassword(),
            body.code(),
            stamp(request),
            googleProof());
    invalidate(request);
    return Map.of("recoveryCodes", codes);
  }

  private GoogleReauthentication.Proof googleProof() {
    return GoogleReauthentication.proof(SecurityContextHolder.getContext().getAuthentication());
  }

  private MfaService.Verified stamp(HttpServletRequest request) {
    var session = request.getSession(false);
    return session != null
            && session.getAttribute(MfaService.VERIFIED) instanceof MfaService.Verified v
        ? v
        : null;
  }

  private void invalidate(HttpServletRequest request) {
    if (request.getSession(false) != null) request.getSession(false).invalidate();
    SecurityContextHolder.clearContext();
  }
}

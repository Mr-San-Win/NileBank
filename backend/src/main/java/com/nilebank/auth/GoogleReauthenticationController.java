package com.nilebank.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class GoogleReauthenticationController {
  private final GoogleReauthentication service;

  public GoogleReauthenticationController(GoogleReauthentication service) {
    this.service = service;
  }

  @PostMapping("/api/v1/auth/security/google/reauthenticate")
  public Map<String, String> prepare(Principal principal, HttpServletRequest request) {
    var intent = service.prepare(UUID.fromString(principal.getName()));
    var session = request.getSession();
    session.removeAttribute(AccountSecurityController.PENDING_LINK);
    session.setAttribute(GoogleReauthentication.SESSION_INTENT, intent);
    return Map.of("redirect", "/oauth2/authorization/google");
  }
}

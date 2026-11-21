package com.nilebank.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.common.ApiError;
import com.nilebank.user.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Revokes stale sessions on their next request, across application instances, without Redis. */
public final class CredentialVersionFilter extends OncePerRequestFilter {
  private final UserRepository users;
  private final ObjectMapper json;

  public CredentialVersionFilter(UserRepository users, ObjectMapper json) {
    this.users = users;
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return "/api/v1/health".equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      try {
        var user = users.findById(UUID.fromString(auth.getName()));
        // Legacy sessions and Spring test principals belong to pre-revocation version zero.
        long version =
            auth.getPrincipal() instanceof VersionedPrincipal p ? p.credentialVersion() : 0;
        if (user.isEmpty() || user.get().getCredentialVersion() != version) {
          if (request.getSession(false) != null) request.getSession(false).invalidate();
          SecurityContextHolder.clearContext();
          response.setStatus(401);
          response.setContentType("application/json");
          json.writeValue(response.getOutputStream(), ApiError.of(401, "Please log in again"));
          return;
        }
      } catch (IllegalArgumentException invalidIdentity) {
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
        response.setStatus(401);
        response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), ApiError.of(401, "Please log in again"));
        return;
      } catch (org.springframework.dao.DataAccessException unavailable) {
        response.setStatus(503);
        response.setContentType("application/json");
        json.writeValue(
            response.getOutputStream(),
            ApiError.of(503, "Authentication is temporarily unavailable"));
        return;
      }
    }
    chain.doFilter(request, response);
  }
}

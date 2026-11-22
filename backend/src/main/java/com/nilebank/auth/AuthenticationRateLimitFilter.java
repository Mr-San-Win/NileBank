package com.nilebank.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.common.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounded per-process IP limit. Uses the trusted servlet remote address, never raw X-Forwarded-For.
 */
public final class AuthenticationRateLimitFilter extends OncePerRequestFilter {
  private record Bucket(long window, int count) {}

  private final Map<String, Bucket> buckets = new HashMap<>();
  private final Clock clock;
  private final int limit;
  private final int capacity;
  private final ObjectMapper json;

  public AuthenticationRateLimitFilter(ObjectMapper json) {
    this(json, Clock.systemUTC(), 60, 10000);
  }

  public AuthenticationRateLimitFilter(ObjectMapper json, Clock clock, int limit, int capacity) {
    this.json = json;
    this.clock = clock;
    this.limit = limit;
    this.capacity = capacity;
  }

  private synchronized boolean allow(String address) {
    long window = clock.millis() / 60000;
    var prior = buckets.get(address);
    if (prior == null || prior.window() != window) {
      buckets.entrySet().removeIf(e -> e.getValue().window() != window);
      if (buckets.size() >= capacity && prior == null) return false;
      buckets.put(address, new Bucket(window, 1));
      return true;
    }
    if (prior.count() >= limit) return false;
    buckets.put(address, new Bucket(window, prior.count() + 1));
    return true;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path =
        request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath();
    boolean sensitive =
        path.equals("/api/v1/auth/login")
            || path.equals("/api/v1/auth/register")
            || path.startsWith("/api/v1/auth/security/")
            || path.startsWith("/api/v1/auth/recovery/")
            || path.startsWith("/api/v1/auth/mfa/")
            || path.equals("/oauth2/authorization/google")
            || path.equals("/login/oauth2/code/google");
    if (sensitive && !allow(request.getRemoteAddr())) {
      response.setStatus(429);
      response.setHeader("Retry-After", "60");
      response.setContentType("application/json");
      json.writeValue(
          response.getOutputStream(),
          ApiError.of(429, "Too many authentication requests; try again later"));
      return;
    }
    chain.doFilter(request, response);
  }
}

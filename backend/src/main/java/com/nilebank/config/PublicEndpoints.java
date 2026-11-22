package com.nilebank.config;

/** Exact public paths; never include protected financial APIs. */
public final class PublicEndpoints {
  private PublicEndpoints() {}

  public static final String[] PATHS = {
    "/",
    "/index.html",
    "/styles.css",
    "/app.js",
    "/extensions.js",
    "/security.js",
    "/currency.js",
    "/planning.js",
    "/savings.js",
    "/favicon.ico",
    "/api/v1/health",
    "/api/v1/auth/csrf",
    "/api/v1/auth/options",
    "/oauth2/authorization/google",
    "/login/oauth2/code/google",
    "/api/v1/auth/register",
    "/api/v1/auth/login",
    "/mfa.js",
    "/api/v1/auth/mfa/status",
    "/api/v1/auth/mfa/challenge",
    "/recovery.js",
    "/api/v1/auth/recovery/request",
    "/api/v1/auth/recovery/reset",
    "/error"
  };

  public static boolean contains(String path) {
    return java.util.Arrays.asList(PATHS).contains(path);
  }
}

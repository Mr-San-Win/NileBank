package com.nilebank.auth.recovery;

import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RecoverySettings {
  public final boolean enabled;
  public final String baseUrl, from, host, username, password;
  public final int port;
  public final boolean tls;

  public RecoverySettings(Environment env) {
    enabled = Boolean.parseBoolean(env.getProperty("nilebank.auth.recovery.enabled", "false"));
    baseUrl =
        env.getProperty("nilebank.auth.recovery.base-url", "http://localhost:8080")
            .replaceAll("/$", "");
    from = env.getProperty("nilebank.auth.recovery.from", "");
    host = env.getProperty("nilebank.auth.recovery.smtp-host", "");
    port = env.getProperty("nilebank.auth.recovery.smtp-port", Integer.class, 587);
    username = env.getProperty("nilebank.auth.recovery.smtp-user", "");
    password = env.getProperty("nilebank.auth.recovery.smtp-password", "");
    tls = env.getProperty("nilebank.auth.recovery.smtp-starttls", Boolean.class, true);
    if (!enabled) return;
    boolean prod = java.util.Arrays.asList(env.getActiveProfiles()).contains("prod");
    URI uri;
    try {
      uri = URI.create(baseUrl);
    } catch (IllegalArgumentException bad) {
      throw invalid();
    }
    boolean https = "https".equals(uri.getScheme());
    boolean local =
        "http".equals(uri.getScheme())
            && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
    if ((!https && !local)
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !uri.getPath().isEmpty()
        || host.isBlank()
        || port < 1
        || port > 65535
        || !from.matches("[^@<>]+@[^@<>]+")
        || from.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
      throw invalid();
    if (!tls && !(host.equals("localhost") || host.equals("127.0.0.1"))) throw invalid();
    if (prod
        && (!https
            || !tls
            || username.isBlank()
            || password.isBlank()
            || !baseUrl.equals(env.getProperty("APP_PUBLIC_URL", "").replaceAll("/$", ""))))
      throw invalid();
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Password recovery configuration is invalid");
  }
}

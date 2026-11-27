package com.nilebank.config;

import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Fail before datasource/migration beans initialize when public deployment settings are unsafe. */
@Configuration(proxyBeanMethods = false)
@Profile({"prod", "render"})
public class ProductionConfig {
  @Bean
  static BeanFactoryPostProcessor productionSettings(Environment environment) {
    return factory -> validate(environment);
  }

  public static void validate(Environment env) {
    String password = env.getProperty("spring.datasource.password", "");
    int minimumLength = managedNeonOnRender(env) ? 16 : 24;
    require(
        password.length() >= minimumLength
            && !password.toLowerCase(java.util.Locale.ROOT).contains("replace"),
        "A strong deployment database password is required");
    require(
        !"postgres".equalsIgnoreCase(env.getProperty("spring.datasource.username", "")),
        "Use a dedicated application database role");
    require(
        env.getProperty("spring.datasource.url", "").startsWith("jdbc:postgresql://"),
        "A PostgreSQL datasource is required");
    require(
        Boolean.parseBoolean(env.getProperty("server.servlet.session.cookie.secure", "false")),
        "Secure session cookies are required");
    String publicUrl = env.getProperty("APP_PUBLIC_URL", "");
    URI uri;
    try {
      uri = URI.create(publicUrl);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("A canonical HTTPS application URL is required");
    }
    require(
        "https".equals(uri.getScheme())
            && uri.getHost() != null
            && uri.getUserInfo() == null
            && uri.getQuery() == null
            && uri.getFragment() == null
            && (uri.getPath().isEmpty() || uri.getPath().equals("/")),
        "A canonical HTTPS application URL is required");
    String strategy = env.getProperty("server.forward-headers-strategy", "");
    require("native".equalsIgnoreCase(strategy), "Explicit native proxy handling is required");
    String proxies = env.getProperty("server.tomcat.remoteip.internal-proxies", "");
    require(
        !proxies.isBlank() && !proxies.contains(".*") && !proxies.contains(".+"),
        "An explicit restricted trusted-proxy expression is required");
    try {
      Pattern.compile(proxies);
    } catch (java.util.regex.PatternSyntaxException invalid) {
      throw new IllegalStateException("Trusted-proxy expression is invalid");
    }
    if (Boolean.parseBoolean(env.getProperty("nilebank.auth.google.enabled", "false"))) {
      String expected = publicUrl.replaceAll("/$", "") + "/login/oauth2/code/google";
      require(
          expected.equals(env.getProperty("nilebank.auth.google.redirect-uri", "")),
          "Google callback must match the canonical HTTPS origin");
      require(
          !env.getProperty("nilebank.auth.google.client-id", "").isBlank()
              && !env.getProperty("nilebank.auth.google.client-secret", "").isBlank(),
          "Google client configuration is required when enabled");
    }
  }

  /**
   * Neon enforces provider-side password entropy. Only permit its shorter generated credentials on
   * Render with a hostname-verified JDBC connection.
   */
  private static boolean managedNeonOnRender(Environment env) {
    if (!env.acceptsProfiles(org.springframework.core.env.Profiles.of("render"))) return false;
    String jdbc = env.getProperty("spring.datasource.url", "");
    if (!jdbc.startsWith("jdbc:postgresql://")) return false;
    try {
      URI uri = URI.create(jdbc.substring(5));
      if (uri.getHost() == null
          || !uri.getHost().endsWith(".neon.tech")
          || uri.getUserInfo() != null
          || uri.getFragment() != null) return false;
      java.util.Map<String, String> parameters = new java.util.HashMap<>();
      for (String parameter :
          java.util.Objects.requireNonNullElse(uri.getRawQuery(), "").split("&")) {
        String[] pair = parameter.split("=", 2);
        String key = java.net.URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8);
        String value =
            pair.length == 2
                ? java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8)
                : "";
        if (parameters.putIfAbsent(key, value) != null) return false;
      }
      return "verify-full".equals(parameters.get("sslmode"))
          && "org.postgresql.ssl.DefaultJavaSSLFactory".equals(parameters.get("sslfactory"));
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}

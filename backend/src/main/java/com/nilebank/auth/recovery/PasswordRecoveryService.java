package com.nilebank.auth.recovery;

import com.nilebank.auth.*;
import com.nilebank.common.ApiException;
import com.nilebank.user.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordRecoveryService {
  private final RecoverySettings settings;
  private final RecoveryMailDelivery delivery;
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final SecureRandom random = new SecureRandom();
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(64),
          runnable -> {
            Thread t = new Thread(runnable, "password-recovery");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());

  public PasswordRecoveryService(
      RecoverySettings settings,
      RecoveryMailDelivery delivery,
      UserRepository users,
      PasswordEncoder encoder,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager) {
    this.settings = settings;
    this.delivery = delivery;
    this.users = users;
    this.encoder = encoder;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(manager);
  }

  public boolean available() {
    return settings.enabled;
  }

  public void request(String email) {
    if (!settings.enabled) return;
    try {
      workers.execute(() -> deliver(AuthService.normalize(email)));
    } catch (RejectedExecutionException full) {
      SecurityEvents.record(SecurityEvents.Event.RECOVERY_UNAVAILABLE, null);
    }
  }

  private record Issued(UUID user, String email, String token, String hash, long version) {}

  private void deliver(String email) {
    try {
      Issued issued =
          tx.execute(
              status -> {
                // Resolve only the UUID before locking; never reuse a pre-lock managed user.
                var candidates =
                    jdbc.query(
                        "select id from app_users where email=?",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        email);
                if (candidates.isEmpty()) return null;
                var user = users.lockById(candidates.getFirst()).orElseThrow();
                if (user.getPasswordHash() == null) return null;
                Long recent =
                    jdbc.queryForObject(
                        "select count(*) from password_reset_tokens where user_id=? and"
                            + " created_at>current_timestamp-interval '1 minute'",
                        Long.class,
                        user.getId());
                if (recent != null && recent > 0) return null;
                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                String hash = hash(token);
                jdbc.update(
                    "update password_reset_tokens set consumed_at=current_timestamp where user_id=?"
                        + " and consumed_at is null",
                    user.getId());
                jdbc.update(
                    "insert into"
                        + " password_reset_tokens(token_hash,user_id,credential_version,expires_at)"
                        + " values(?,?,?,current_timestamp+interval '15 minutes')",
                    hash,
                    user.getId(),
                    user.getCredentialVersion());
                return new Issued(
                    user.getId(), user.getEmail(), token, hash, user.getCredentialVersion());
              });
      if (issued == null) return;
      delivery.send(issued.email, issued.token);
      tx.executeWithoutResult(
          status -> {
            var user = users.lockById(issued.user).orElseThrow();
            if (user.getCredentialVersion() == issued.version)
              jdbc.update(
                  "update password_reset_tokens set delivered_at=current_timestamp where"
                      + " token_hash=? and consumed_at is null",
                  issued.hash);
          });
    } catch (RuntimeException failure) {
      SecurityEvents.record(SecurityEvents.Event.RECOVERY_UNAVAILABLE, null);
    }
  }

  public void reset(String token, String password) {
    if (!settings.enabled) throw invalid();
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
    if (password == null
        || password.length() < 12
        || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "Password must contain at least 12 characters and fit within 72 UTF-8 bytes");
    String hash = hash(token);
    tx.executeWithoutResult(
        status -> {
          var owners =
              jdbc.query(
                  "select user_id from password_reset_tokens where token_hash=?",
                  (rs, n) -> rs.getObject(1, UUID.class),
                  hash);
          if (owners.isEmpty()) throw invalid();
          var user =
              users.lockById(owners.getFirst()).orElseThrow(PasswordRecoveryService::invalid);
          var valid =
              jdbc.queryForObject(
                  "select count(*) from password_reset_tokens where token_hash=? and user_id=? and"
                      + " credential_version=? and delivered_at is not null and consumed_at is null"
                      + " and expires_at>current_timestamp",
                  Long.class,
                  hash,
                  user.getId(),
                  user.getCredentialVersion());
          if (user.getPasswordHash() == null || valid == null || valid != 1) throw invalid();
          if (encoder.matches(password, user.getPasswordHash()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a different password");
          user.changePassword(encoder.encode(password));
          users.saveAndFlush(user);
          jdbc.update(
              "update password_reset_tokens set consumed_at=current_timestamp where user_id=? and"
                  + " consumed_at is null",
              user.getId());
          SecurityEvents.record(SecurityEvents.Event.PASSWORD_RESET, user.getId());
        });
  }

  static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Required digest unavailable");
    }
  }

  private static ApiException invalid() {
    return new ApiException(
        HttpStatus.BAD_REQUEST, "Reset link is invalid or expired; request a new email");
  }

  @jakarta.annotation.PreDestroy
  public void close() {
    workers.shutdownNow();
  }
}

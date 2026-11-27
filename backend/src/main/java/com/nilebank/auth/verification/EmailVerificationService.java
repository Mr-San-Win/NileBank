package com.nilebank.auth.verification;

import com.nilebank.auth.*;
import com.nilebank.auth.recovery.*;
import com.nilebank.common.ApiException;
import com.nilebank.user.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailVerificationService {
  private final RecoverySettings settings;
  private final RecoveryMailDelivery mail;
  private final UserRepository users;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final SecureRandom random = new SecureRandom();
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(32),
          r -> {
            Thread t = new Thread(r, "email-verification");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());

  public record Status(boolean available, boolean verified, Instant verifiedAt) {}

  private record Issued(UUID owner, String email, long version, String token, String hash) {}

  public EmailVerificationService(
      RecoverySettings settings,
      RecoveryMailDelivery mail,
      UserRepository users,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager) {
    this.settings = settings;
    this.mail = mail;
    this.users = users;
    this.jdbc = jdbc;
    tx = new TransactionTemplate(manager);
  }

  public Status status(UUID owner) {
    return tx.execute(
        s -> {
          var user = users.findById(owner).orElseThrow(EmailVerificationService::invalid);
          return new Status(
              settings.verificationEnabled,
              user.getEmailVerifiedAt() != null,
              user.getEmailVerifiedAt());
        });
  }

  public void request(UUID owner) {
    if (!settings.verificationEnabled) return;
    try {
      workers.execute(() -> deliver(owner));
    } catch (RejectedExecutionException full) {
      SecurityEvents.record(SecurityEvents.Event.VERIFICATION_UNAVAILABLE, null);
    }
  }

  private void deliver(UUID owner) {
    try {
      Issued issued =
          tx.execute(
              s -> {
                var user = users.lockById(owner).orElseThrow(EmailVerificationService::invalid);
                if (user.getEmailVerifiedAt() != null) return null;
                long recent =
                    jdbc.queryForObject(
                        "select count(*) from email_verification_tokens where user_id=? and"
                            + " created_at>current_timestamp-interval '1 minute'",
                        Long.class,
                        owner);
                if (recent > 0) return null;
                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                    hash = hash(token);
                jdbc.update(
                    "update email_verification_tokens set consumed_at=current_timestamp where"
                        + " user_id=? and consumed_at is null",
                    owner);
                jdbc.update(
                    "insert into"
                        + " email_verification_tokens(token_hash,user_id,email_snapshot,credential_version,expires_at)"
                        + " values(?,?,?,?,current_timestamp+interval '24 hours')",
                    hash,
                    owner,
                    user.getEmail(),
                    user.getCredentialVersion());
                return new Issued(owner, user.getEmail(), user.getCredentialVersion(), token, hash);
              });
      if (issued == null) return;
      mail.verifyEmail(issued.email, issued.token);
      tx.executeWithoutResult(
          s -> {
            var user = users.lockById(owner).orElseThrow(EmailVerificationService::invalid);
            if (user.getCredentialVersion() == issued.version
                && user.getEmail().equals(issued.email))
              jdbc.update(
                  "update email_verification_tokens set delivered_at=current_timestamp where"
                      + " token_hash=? and consumed_at is null",
                  issued.hash);
          });
    } catch (RuntimeException failed) {
      SecurityEvents.record(SecurityEvents.Event.VERIFICATION_UNAVAILABLE, null);
    }
  }

  public void confirm(String token) {
    if (!settings.verificationEnabled || token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw invalid();
    String hash = hash(token);
    tx.executeWithoutResult(
        s -> {
          var owners =
              jdbc.query(
                  "select user_id from email_verification_tokens where token_hash=?",
                  (rs, n) -> rs.getObject(1, UUID.class),
                  hash);
          if (owners.isEmpty()) throw invalid();
          var user =
              users.lockById(owners.getFirst()).orElseThrow(EmailVerificationService::invalid);
          long valid =
              jdbc.queryForObject(
                  "select count(*) from email_verification_tokens where token_hash=? and user_id=?"
                      + " and email_snapshot=? and credential_version=? and delivered_at is not"
                      + " null and consumed_at is null and expires_at>current_timestamp",
                  Long.class,
                  hash,
                  user.getId(),
                  user.getEmail(),
                  user.getCredentialVersion());
          if (valid != 1) throw invalid();
          user.verifyEmail();
          users.saveAndFlush(user);
          jdbc.update(
              "update email_verification_tokens set consumed_at=current_timestamp where user_id=?"
                  + " and consumed_at is null",
              user.getId());
          SecurityEvents.record(SecurityEvents.Event.EMAIL_VERIFIED, user.getId());
        });
  }

  private static String hash(String token) {
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
        HttpStatus.BAD_REQUEST,
        "Email confirmation link is invalid or expired; request a new email");
  }

  @jakarta.annotation.PreDestroy
  public void close() {
    workers.shutdownNow();
  }
}

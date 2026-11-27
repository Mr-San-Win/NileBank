package com.nilebank.auth.mfa;

import com.nilebank.auth.*;
import com.nilebank.common.ApiException;
import com.nilebank.user.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MfaService {
  public static final String VERIFIED = "nilebank.mfa.verified";

  public record Pending(Authentication original, Instant expires, AtomicInteger attempts)
      implements java.io.Serializable {}

  public record Verified(UUID owner, long version, Instant at) implements java.io.Serializable {}

  public record Settings(
      boolean available,
      boolean enabled,
      long recoveryCodesRemaining,
      boolean passwordRequired,
      boolean googleVerified) {}

  public record Enrollment(
      String secret, String issuer, String account, int digits, int period, Instant expiresAt) {}

  private record Row(
      String encrypted,
      String key,
      String status,
      Instant expires,
      Long last,
      int failures,
      Instant window) {}

  private record Result(boolean ok, boolean limited) {}

  private final UserRepository users;
  private final GoogleReauthentication google;
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final MfaSecrets secrets;
  private final TransactionTemplate tx;
  private final SecureRandom random = new SecureRandom();

  public MfaService(
      UserRepository users,
      JdbcTemplate jdbc,
      PasswordEncoder passwords,
      MfaSecrets secrets,
      PlatformTransactionManager manager,
      GoogleReauthentication google) {
    this.users = users;
    this.google = google;
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.secrets = secrets;
    tx = new TransactionTemplate(manager);
  }

  private BankUser lock(UUID owner) {
    return users.lockById(owner).orElseThrow(MfaService::invalid);
  }

  private Optional<Row> row(UUID owner) {
    return jdbc
        .query(
            "select * from mfa_settings where user_id=?",
            (rs, n) ->
                new Row(
                    rs.getString("encrypted_secret"),
                    rs.getString("key_id"),
                    rs.getString("status"),
                    rs.getTimestamp("enrollment_expires_at").toInstant(),
                    rs.getObject("last_counter", Long.class),
                    rs.getInt("failed_attempts"),
                    rs.getTimestamp("failure_window").toInstant()),
            owner)
        .stream()
        .findFirst();
  }

  private void proof(BankUser user, String password, GoogleReauthentication.Proof googleProof) {
    if (user.getPasswordHash() == null) {
      if (!google.valid(user, googleProof))
        throw new ApiException(
            HttpStatus.UNAUTHORIZED,
            "Complete recent Google verification before changing authenticator protection");
      return;
    }
    if (password == null
        || password.getBytes(StandardCharsets.UTF_8).length > 72
        || !passwords.matches(password, user.getPasswordHash()))
      throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
  }

  public Settings settings(UUID owner) {
    return settings(owner, null);
  }

  public Settings settings(UUID owner, GoogleReauthentication.Proof proof) {
    return tx.execute(
        s -> {
          var user = users.findById(owner).orElseThrow(MfaService::invalid);
          var row = row(owner);
          boolean active = row.isPresent() && row.get().status.equals("ACTIVE");
          return new Settings(
              secrets.enrollmentAvailable()
                  && (user.getPasswordHash() != null || google.supports(owner)),
              active,
              active
                  ? jdbc.queryForObject(
                      "select count(*) from mfa_recovery_codes where user_id=? and consumed_at is"
                          + " null",
                      Long.class,
                      owner)
                  : 0,
              user.getPasswordHash() != null,
              google.valid(user, proof));
        });
  }

  public Enrollment begin(UUID owner, String password) {
    return begin(owner, password, null);
  }

  public Enrollment begin(UUID owner, String password, GoogleReauthentication.Proof googleProof) {
    return tx.execute(
        s -> {
          var user = lock(owner);
          proof(user, password, googleProof);
          if (!secrets.enrollmentAvailable()) throw MfaSecrets.unavailable();
          if (row(owner).map(r -> r.status.equals("ACTIVE")).orElse(false))
            throw new ApiException(HttpStatus.CONFLICT, "Authenticator is already enabled");
          byte[] bytes = new byte[20];
          random.nextBytes(bytes);
          var encrypted = secrets.encrypt(owner, bytes);
          String secret = Totp.base32(bytes);
          Arrays.fill(bytes, (byte) 0);
          Instant expiry = Instant.now().plusSeconds(600);
          jdbc.update("delete from mfa_settings where user_id=?", owner);
          jdbc.update(
              "insert into"
                  + " mfa_settings(user_id,encrypted_secret,key_id,status,enrollment_expires_at)"
                  + " values(?,?,?,'PENDING',?)",
              owner,
              encrypted.ciphertext(),
              encrypted.keyId(),
              java.sql.Timestamp.from(expiry));
          return new Enrollment(secret, "NileBank", user.getEmail(), 6, 30, expiry);
        });
  }

  public List<String> confirm(UUID owner, String password, String code) {
    return confirm(owner, password, code, null);
  }

  public List<String> confirm(
      UUID owner, String password, String code, GoogleReauthentication.Proof googleProof) {
    var result =
        tx.execute(
            s -> {
              var user = lock(owner);
              proof(user, password, googleProof);
              Row row = row(owner).orElseThrow(MfaService::invalid);
              if (!row.status.equals("PENDING") || !row.expires.isAfter(Instant.now()))
                throw invalid();
              Result checked = check(owner, row, code, false);
              if (!checked.ok) return new Confirmation(null, checked);
              jdbc.update("update mfa_settings set status='ACTIVE' where user_id=?", owner);
              var codes = codes(owner);
              user.revokeSessions();
              users.saveAndFlush(user);
              SecurityEvents.record(SecurityEvents.Event.MFA_ENABLED, owner);
              return new Confirmation(codes, checked);
            });
    require(result.result);
    return result.codes;
  }

  private record Confirmation(List<String> codes, Result result) {}

  public static Pending pendingState(Authentication authentication) {
    if (authentication == null) return null;
    if (authentication.getPrincipal() instanceof PendingOidcUser oidc) return oidc.pending();
    return authentication.getDetails() instanceof Pending pending ? pending : null;
  }

  public static boolean pending(Authentication authentication) {
    return pendingState(authentication) != null
        && authentication.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_MFA_PENDING"));
  }

  public Authentication gate(Authentication original) {
    return tx.execute(
        s -> {
          UUID owner = UUID.fromString(original.getName());
          var user = lock(owner);
          long version = version(original);
          if (user.getCredentialVersion() != version) throw invalid();
          var row = row(owner);
          if (row.isEmpty() || !row.get().status.equals("ACTIVE")) return original;
          if (!secrets.readable(row.get().key)) throw MfaSecrets.unavailable();
          var roles = List.of(new SimpleGrantedAuthority("ROLE_MFA_PENDING"));
          Pending state =
              new Pending(original, Instant.now().plusSeconds(300), new AtomicInteger());
          AbstractAuthenticationToken pending;
          if (original instanceof OAuth2AuthenticationToken oauth)
            pending =
                new OAuth2AuthenticationToken(
                    new PendingOidcUser(
                        (org.springframework.security.oauth2.core.oidc.user.OidcUser)
                            oauth.getPrincipal(),
                        state),
                    roles,
                    oauth.getAuthorizedClientRegistrationId());
          else
            pending =
                UsernamePasswordAuthenticationToken.authenticated(
                    original.getPrincipal(), null, roles);
          pending.setDetails(state);
          return pending;
        });
  }

  public static long version(Authentication auth) {
    return auth.getPrincipal() instanceof VersionedPrincipal p ? p.credentialVersion() : 0;
  }

  public void verify(UUID owner, long version, String code) {
    Result result =
        tx.execute(
            s -> {
              var user = lock(owner);
              if (user.getCredentialVersion() != version) throw invalid();
              var row = row(owner).orElseThrow(MfaService::invalid);
              if (!row.status.equals("ACTIVE")) throw invalid();
              return check(owner, row, code, true);
            });
    require(result);
  }

  public void disable(UUID owner, String password, String code, Verified verified) {
    disable(owner, password, code, verified, null);
  }

  public void disable(
      UUID owner,
      String password,
      String code,
      Verified verified,
      GoogleReauthentication.Proof googleProof) {
    Result result =
        tx.execute(
            s -> {
              var user = lock(owner);
              proof(user, password, googleProof);
              var row = row(owner).orElseThrow(MfaService::invalid);
              if (!row.status.equals("ACTIVE")) throw invalid();
              Result checked =
                  fresh(user, verified) ? new Result(true, false) : check(owner, row, code, true);
              if (!checked.ok) return checked;
              jdbc.update("delete from mfa_settings where user_id=?", owner);
              user.revokeSessions();
              users.saveAndFlush(user);
              SecurityEvents.record(SecurityEvents.Event.MFA_DISABLED, owner);
              return checked;
            });
    require(result);
  }

  public List<String> regenerate(UUID owner, String password, String code, Verified verified) {
    return regenerate(owner, password, code, verified, null);
  }

  public List<String> regenerate(
      UUID owner,
      String password,
      String code,
      Verified verified,
      GoogleReauthentication.Proof googleProof) {
    var result =
        tx.execute(
            s -> {
              var user = lock(owner);
              proof(user, password, googleProof);
              var row = row(owner).orElseThrow(MfaService::invalid);
              if (!row.status.equals("ACTIVE")) throw invalid();
              Result checked =
                  fresh(user, verified) ? new Result(true, false) : check(owner, row, code, true);
              if (!checked.ok) return new Confirmation(null, checked);
              var codes = codes(owner);
              user.revokeSessions();
              users.saveAndFlush(user);
              SecurityEvents.record(SecurityEvents.Event.MFA_CODES_REPLACED, owner);
              return new Confirmation(codes, checked);
            });
    require(result.result);
    return result.codes;
  }

  private boolean fresh(BankUser user, Verified stamp) {
    return stamp != null
        && stamp.owner.equals(user.getId())
        && stamp.version == user.getCredentialVersion()
        && stamp.at.isAfter(Instant.now().minusSeconds(300))
        && !stamp.at.isAfter(Instant.now());
  }

  private Result check(UUID owner, Row row, String code, boolean backupAllowed) {
    Instant now = Instant.now();
    int failures = row.window.isAfter(now.minusSeconds(60)) ? row.failures : 0;
    if (failures >= 10) return new Result(false, true);
    boolean accepted = false;
    if (code != null && code.matches("[0-9]{6}")) {
      byte[] bytes = secrets.decrypt(owner, row.key, row.encrypted);
      long counter;
      try {
        counter =
            Totp.match(bytes, code, now.getEpochSecond() / 30, row.last == null ? -1 : row.last);
      } finally {
        Arrays.fill(bytes, (byte) 0);
      }
      if (counter >= 0) {
        jdbc.update("update mfa_settings set last_counter=? where user_id=?", counter, owner);
        accepted = true;
      }
    } else if (backupAllowed && code != null) {
      String normalized = code.toUpperCase(Locale.ROOT).replace("-", "");
      if (normalized.startsWith("NBRC")) normalized = normalized.substring(4);
      if (normalized.matches("[0-9A-F]{32}"))
        accepted =
            jdbc.update(
                    "update mfa_recovery_codes set consumed_at=current_timestamp where user_id=?"
                        + " and code_hash=? and consumed_at is null",
                    owner,
                    digest(owner, normalized))
                == 1;
    }
    if (accepted) {
      jdbc.update(
          "update mfa_settings set failed_attempts=0,failure_window=current_timestamp where"
              + " user_id=?",
          owner);
      return new Result(true, false);
    }
    jdbc.update(
        "update mfa_settings set failed_attempts=?,failure_window=? where user_id=?",
        failures + 1,
        java.sql.Timestamp.from(failures == 0 ? now : row.window),
        owner);
    SecurityEvents.record(SecurityEvents.Event.MFA_FAILED, owner);
    return new Result(false, failures + 1 >= 10);
  }

  private List<String> codes(UUID owner) {
    jdbc.update("delete from mfa_recovery_codes where user_id=?", owner);
    List<String> result = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      byte[] bytes = new byte[16];
      random.nextBytes(bytes);
      String raw = HexFormat.of().withUpperCase().formatHex(bytes);
      jdbc.update(
          "insert into mfa_recovery_codes(user_id,code_hash) values(?,?)",
          owner,
          digest(owner, raw));
      result.add("NBRC-" + raw);
      Arrays.fill(bytes, (byte) 0);
    }
    return List.copyOf(result);
  }

  private static String digest(UUID owner, String code) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((owner + ":" + code).getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Required digest unavailable");
    }
  }

  private static void require(Result result) {
    if (!result.ok)
      throw new ApiException(
          result.limited ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_REQUEST,
          result.limited
              ? "Too many authenticator attempts; wait a minute"
              : "Authenticator or recovery code is invalid");
  }

  static ApiException invalid() {
    return new ApiException(
        HttpStatus.BAD_REQUEST, "Authentication step expired or unavailable; sign in again");
  }
}

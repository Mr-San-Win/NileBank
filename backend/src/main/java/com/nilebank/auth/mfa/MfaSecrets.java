package com.nilebank.auth.mfa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** AES-GCM seeds with per-record nonce, owner-bound AAD and environment-only keys. */
@Component
public final class MfaSecrets {
  private final Map<String, SecretKeySpec> keys;
  private final String active;
  private final boolean enrollment;
  private final SecureRandom random = new SecureRandom();

  public record Encrypted(String keyId, String ciphertext) {}

  public MfaSecrets(Environment env, ObjectMapper mapper) {
    String raw = env.getProperty("nilebank.auth.mfa.keys-json", "");
    active = env.getProperty("nilebank.auth.mfa.active-key-id", "");
    enrollment = env.getProperty("nilebank.auth.mfa.enabled", Boolean.class, false);
    Map<String, SecretKeySpec> parsed = new HashMap<>();
    try {
      if (!raw.isBlank()) {
        var json =
            mapper
                .copy()
                .enable(
                    com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readTree(raw);
        if (!json.isObject() || json.size() > 8 || json.isEmpty())
          throw new IllegalArgumentException();
        var fields = json.fields();
        while (fields.hasNext()) {
          var field = fields.next();
          if (!field.getKey().matches("[A-Za-z0-9_-]{1,32}") || !field.getValue().isTextual())
            throw new IllegalArgumentException();
          byte[] bytes = Base64.getDecoder().decode(field.getValue().asText());
          if (bytes.length != 32) throw new IllegalArgumentException();
          parsed.put(field.getKey(), new SecretKeySpec(bytes, "AES"));
          Arrays.fill(bytes, (byte) 0);
        }
        if (!parsed.containsKey(active)) throw new IllegalArgumentException();
      } else if (enrollment || !active.isBlank()) throw new IllegalArgumentException();
    } catch (Exception invalid) {
      throw new IllegalStateException("MFA key configuration is invalid");
    }
    keys = Map.copyOf(parsed);
  }

  public boolean enrollmentAvailable() {
    return enrollment && keys.containsKey(active);
  }

  public boolean readable(String id) {
    return keys.containsKey(id);
  }

  public Encrypted encrypt(UUID owner, byte[] secret) {
    if (!enrollmentAvailable()) throw unavailable();
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, keys.get(active), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad(owner, active));
      byte[] encrypted = cipher.doFinal(secret);
      byte[] all = new byte[nonce.length + encrypted.length];
      System.arraycopy(nonce, 0, all, 0, nonce.length);
      System.arraycopy(encrypted, 0, all, nonce.length, encrypted.length);
      return new Encrypted(active, Base64.getEncoder().encodeToString(all));
    } catch (java.security.GeneralSecurityException invalid) {
      throw unavailable();
    }
  }

  public byte[] decrypt(UUID owner, String id, String value) {
    try {
      if (!keys.containsKey(id)) throw unavailable();
      byte[] all = Base64.getDecoder().decode(value);
      if (all.length != 48) throw unavailable();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, keys.get(id), new GCMParameterSpec(128, Arrays.copyOf(all, 12)));
      cipher.updateAAD(aad(owner, id));
      return cipher.doFinal(Arrays.copyOfRange(all, 12, all.length));
    } catch (java.security.GeneralSecurityException | IllegalArgumentException invalid) {
      throw unavailable();
    }
  }

  private static byte[] aad(UUID owner, String id) {
    return ("nilebank-totp-v1:" + owner + ":" + id).getBytes(StandardCharsets.UTF_8);
  }

  static ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE, "Authenticator service is unavailable; try again later");
  }
}

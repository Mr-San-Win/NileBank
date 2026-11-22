package com.nilebank.auth.mfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238: SHA-1, six digits, 30-second steps. */
public final class Totp {
  private Totp() {}

  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  public static String base32(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    int buffer = 0, bits = 0;
    for (byte value : bytes) {
      buffer = (buffer << 8) | (value & 255);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        result.append(ALPHABET.charAt((buffer >> bits) & 31));
      }
    }
    if (bits > 0) result.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
    return result.toString();
  }

  public static byte[] decode(String value) {
    if (value == null || !value.matches("[A-Z2-7]{32}"))
      throw new IllegalArgumentException("Invalid authenticator seed");
    byte[] result = new byte[20];
    int buffer = 0, bits = 0, index = 0;
    for (char c : value.toCharArray()) {
      buffer = (buffer << 5) | ALPHABET.indexOf(c);
      bits += 5;
      if (bits >= 8) {
        bits -= 8;
        result[index++] = (byte) (buffer >> bits);
      }
    }
    return result;
  }

  public static String code(byte[] secret, long counter) {
    if (secret == null || secret.length < 16 || counter < 0)
      throw new IllegalArgumentException("Invalid authenticator inputs");
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
      int offset = hash[hash.length - 1] & 15;
      int number =
          ((hash[offset] & 127) << 24)
              | ((hash[offset + 1] & 255) << 16)
              | ((hash[offset + 2] & 255) << 8)
              | (hash[offset + 3] & 255);
      return String.format(java.util.Locale.ROOT, "%06d", number % 1000000);
    } catch (java.security.GeneralSecurityException unavailable) {
      throw new IllegalStateException("Required authenticator algorithm unavailable");
    }
  }

  public static long match(byte[] secret, String supplied, long counter, long last) {
    if (supplied == null || !supplied.matches("[0-9]{6}")) return -1;
    for (long candidate = Math.max(0, counter - 1); candidate <= counter + 1; candidate++)
      if (candidate > last
          && MessageDigest.isEqual(
              code(secret, candidate).getBytes(StandardCharsets.US_ASCII),
              supplied.getBytes(StandardCharsets.US_ASCII))) return candidate;
    return -1;
  }
}

package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.auth.mfa.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MfaCryptoTest {
  static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);

  static MockEnvironment environment() {
    return new MockEnvironment()
        .withProperty("nilebank.auth.mfa.enabled", "true")
        .withProperty("nilebank.auth.mfa.active-key-id", "test")
        .withProperty("nilebank.auth.mfa.keys-json", "{\"test\":\"" + TEST_KEY + "\"}");
  }

  @Test
  void rfc6238Sha1VectorsMatchSixDigitSuffix() {
    byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    long[] times = {59, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
    String[] expected = {"287082", "081804", "050471", "005924", "279037", "353130"};
    for (int i = 0; i < times.length; i++)
      assertThat(Totp.code(secret, times[i] / 30)).isEqualTo(expected[i]);
  }

  @Test
  void base32RoundTripAndInvalidInputs() {
    byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    assertThat(Totp.decode(Totp.base32(secret))).isEqualTo(secret);
    assertThat(Totp.base32(secret)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    assertThatThrownBy(() -> Totp.decode("invalid")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void replayWindowRejectsUsedOldAndMalformedCodes() {
    byte[] secret = new byte[20];
    String code = Totp.code(secret, 100);
    assertThat(Totp.match(secret, code, 100, 99)).isEqualTo(100);
    assertThat(Totp.match(secret, code, 100, 100)).isEqualTo(-1);
    assertThat(Totp.match(secret, code, 103, 99)).isEqualTo(-1);
    assertThat(Totp.match(secret, " 12345", 100, 0)).isEqualTo(-1);
  }

  @Test
  void encryptedSeedsAreRandomizedAndBoundToOwner() {
    var cipher = new MfaSecrets(environment(), new ObjectMapper());
    var owner = UUID.randomUUID();
    byte[] secret = new byte[20];
    var first = cipher.encrypt(owner, secret);
    var second = cipher.encrypt(owner, secret);
    assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    assertThat(cipher.decrypt(owner, first.keyId(), first.ciphertext())).isEqualTo(secret);
    assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), first.keyId(), first.ciphertext()))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    byte[] tampered = Base64.getDecoder().decode(first.ciphertext());
    tampered[20] ^= 1;
    assertThatThrownBy(
            () ->
                cipher.decrypt(owner, first.keyId(), Base64.getEncoder().encodeToString(tampered)))
        .isInstanceOf(com.nilebank.common.ApiException.class);
  }

  @Test
  void keyRotationRetainsOldDecryptionAndDisablingEnrollmentDoesNotRemoveKeys() {
    var owner = UUID.randomUUID();
    var old = new MfaSecrets(environment(), new ObjectMapper());
    var encrypted = old.encrypt(owner, new byte[20]);
    var env =
        environment()
            .withProperty("nilebank.auth.mfa.enabled", "false")
            .withProperty("nilebank.auth.mfa.active-key-id", "new")
            .withProperty(
                "nilebank.auth.mfa.keys-json",
                "{\"test\":\""
                    + TEST_KEY
                    + "\",\"new\":\""
                    + Base64.getEncoder()
                        .encodeToString(
                            "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII))
                    + "\"}");
    var rotated = new MfaSecrets(env, new ObjectMapper());
    assertThat(rotated.enrollmentAvailable()).isFalse();
    assertThat(rotated.decrypt(owner, "test", encrypted.ciphertext())).hasSize(20);
    assertThatThrownBy(() -> rotated.encrypt(owner, new byte[20]))
        .isInstanceOf(com.nilebank.common.ApiException.class);
  }

  @Test
  void malformedDuplicateMissingAndWrongLengthKeysFailWithoutValues() {
    for (String json :
        List.of(
            "{}",
            "[]",
            "{\"test\":\"private-invalid-value\"}",
            "{\"other\":\"" + TEST_KEY + "\"}",
            "{\"test\":\"" + TEST_KEY + "\",\"test\":\"" + TEST_KEY + "\"}",
            "{\"test\":\"" + TEST_KEY + "\"} {}"))
      assertThatThrownBy(
              () ->
                  new MfaSecrets(
                      environment().withProperty("nilebank.auth.mfa.keys-json", json),
                      new ObjectMapper()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining("private-invalid-value");
    assertThatCode(() -> new MfaSecrets(new MockEnvironment(), new ObjectMapper()))
        .doesNotThrowAnyException();
  }
}

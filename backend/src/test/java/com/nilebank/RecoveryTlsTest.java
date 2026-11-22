package com.nilebank;

import static org.assertj.core.api.Assertions.*;

import com.nilebank.auth.recovery.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RecoveryTlsTest {
  @Test
  void requiredStartTlsNeverFallsBackToPlaintextDelivery() throws Exception {
    try (var sink = new LocalSmtpSink()) {
      var env =
          new MockEnvironment()
              .withProperty("nilebank.auth.recovery.enabled", "true")
              .withProperty("nilebank.auth.recovery.smtp-host", "127.0.0.1")
              .withProperty("nilebank.auth.recovery.smtp-port", String.valueOf(sink.port()))
              .withProperty("nilebank.auth.recovery.from", "demo@example.test")
              .withProperty("nilebank.auth.recovery.smtp-starttls", "true");
      var mail = new RecoveryMailDelivery(new RecoverySettings(env));
      assertThatThrownBy(() -> mail.send("fictional@example.test", "A".repeat(43)))
          .isInstanceOf(org.springframework.mail.MailException.class);
      assertThat(sink.messages).isEmpty();
    }
  }
}

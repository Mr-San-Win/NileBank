package com.nilebank.auth;

import java.util.UUID;
import org.slf4j.*;
import org.springframework.transaction.support.*;

/**
 * Allowlisted events only: never log email, password, provider claims, codes, cookies or tokens.
 */
public final class SecurityEvents {
  public enum Event {
    MFA_REQUIRED,
    MFA_FAILED,
    MFA_ENABLED,
    MFA_DISABLED,
    MFA_CODES_REPLACED,
    EMAIL_VERIFIED,
    VERIFICATION_UNAVAILABLE,
    LOGIN,
    LOGIN_FAILED,
    PASSWORD_CHANGED,
    GOOGLE_LINKED,
    RECOVERY_UNAVAILABLE,
    PASSWORD_RESET,
    GOOGLE_UNLINKED
  }

  private static final Logger LOG = LoggerFactory.getLogger(SecurityEvents.class);

  private SecurityEvents() {}

  public static void record(Event event, UUID user) {
    Runnable log =
        () -> LOG.info("security_event={} user_id={}", event, user == null ? "anonymous" : user);
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              log.run();
            }
          });
    else log.run();
  }
}

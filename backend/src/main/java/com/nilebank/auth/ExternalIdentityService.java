package com.nilebank.auth;

import com.nilebank.common.ApiException;
import com.nilebank.user.*;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal provisioning only: does not authenticate a session or link an existing user. */
@Service
public class ExternalIdentityService {
  private final UserRepository users;
  private final ExternalIdentityRepository identities;
  private final Validator validator;
  private final TransactionTemplate transactions;

  public ExternalIdentityService(
      UserRepository users,
      ExternalIdentityRepository identities,
      Validator validator,
      PlatformTransactionManager manager) {
    this.users = users;
    this.identities = identities;
    this.validator = validator;
    this.transactions = new TransactionTemplate(manager);
    // Own the transaction so a failed PostgreSQL insert is fully rolled back before recovery,
    // even if a future caller already has a transaction. No persistence context is reused.
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public AuthService.UserView resolveOrProvision(ValidatedExternalIdentity identity) {
    if (identity == null || !validator.validate(identity).isEmpty())
      throw ValidatedExternalIdentity.invalid();
    try {
      return transactions.execute(
          status -> {
            var existing =
                identities.findByIssuerAndProviderSubject(identity.issuer(), identity.subject());
            if (existing.isPresent()) return AuthService.view(existing.get().getUser());
            // The unique email constraint rejects collisions without email-based resolution.
            // A read-before-insert rejection could race with a just-committed same-subject login.
            var user =
                users.saveAndFlush(new BankUser(identity.fullName(), identity.email(), null));
            identities.saveAndFlush(new ExternalIdentity(user, identity));
            return AuthService.view(user);
          });
    } catch (DataIntegrityViolationException conflict) {
      // execute has already rolled back. A concurrent winner may now own the identity.
      return transactions.execute(
          status ->
              identities
                  .findByIssuerAndProviderSubject(identity.issuer(), identity.subject())
                  .map(existing -> AuthService.view(existing.getUser()))
                  .orElseThrow(ExternalIdentityService::collision));
    }
  }

  private static ApiException collision() {
    return new ApiException(
        HttpStatus.CONFLICT,
        "External sign-in could not be completed. Use your existing sign-in method.");
  }
}

package com.nilebank;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilebank.auth.*;
import com.nilebank.common.ApiException;
import com.nilebank.user.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

class ExternalIdentityTest extends PostgresTest {
  @Autowired ExternalIdentityService service;
  @MockitoSpyBean ExternalIdentityRepository identities;
  @Autowired UserRepository users;
  @Autowired AuthService passwords;
  @Autowired PasswordEncoder encoder;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  static final String ISSUER = "https://accounts.google.com";

  String email() {
    return UUID.randomUUID() + "@example.test";
  }

  ValidatedExternalIdentity identity(String subject, String email) {
    return ValidatedExternalIdentity.google(ISSUER, subject, email, true, "Google Demo Student");
  }

  ValidatedExternalIdentity identity() {
    return identity(UUID.randomUUID().toString(), email());
  }

  void conflict(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void provisionsOneInternalUserAndIdentityWithoutPasswordOrBankAccount() {
    var input = identity(UUID.randomUUID().toString(), email().toUpperCase(Locale.ROOT));
    var result = service.resolveOrProvision(input);
    var user = users.findById(result.id()).orElseThrow();
    assertThat(user.getPasswordHash()).isNull();
    assertThat(user.getEmail()).isEqualTo(input.email());
    assertThat(
            identities
                .findByIssuerAndProviderSubject(ISSUER, input.subject())
                .orElseThrow()
                .getProvider())
        .isEqualTo("google");
    assertThat(
            jdbc.queryForObject(
                "select user_id from auth_identities where issuer=? and provider_subject=?",
                UUID.class,
                ISSUER,
                input.subject()))
        .isEqualTo(result.id());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from bank_accounts where owner_id=?", Long.class, result.id()))
        .isZero();
  }

  @Test
  void returningIdentityPreservesUuidAndProfileEvenWhenProviderEmailBelongsToSomeoneElse() {
    var input = identity();
    var first = passwords.me(service.resolveOrProvision(input).id());
    String otherEmail = email();
    passwords.register("Existing Demo Student", otherEmail, "Example-passphrase-21");
    var changed =
        ValidatedExternalIdentity.google(
            ISSUER, input.subject(), otherEmail, true, "Changed Provider Name");
    assertThat(service.resolveOrProvision(changed)).isEqualTo(first);
    assertThat(users.findById(first.id()).orElseThrow().getEmail()).isEqualTo(input.email());
  }

  @Test
  void passwordEmailCollisionNeverLinksOrChangesExistingUser() {
    var email = email();
    var existing =
        passwords.me(
            passwords.register("Password Demo Student", email, "Example-passphrase-21").id());
    var hash = users.findById(existing.id()).orElseThrow().getPasswordHash();
    var input = identity(UUID.randomUUID().toString(), email.toUpperCase(Locale.ROOT));
    conflict(() -> service.resolveOrProvision(input));
    assertThat(identities.findByIssuerAndProviderSubject(ISSUER, input.subject())).isEmpty();
    assertThat(users.findById(existing.id()).orElseThrow().getPasswordHash()).isEqualTo(hash);
    assertThat(passwords.me(existing.id())).isEqualTo(existing);
  }

  @Test
  void externalUserEmailCollisionDoesNotBindANewSubject() {
    var input = identity();
    var first = service.resolveOrProvision(input);
    var second = identity(UUID.randomUUID().toString(), input.email());
    conflict(() -> service.resolveOrProvision(second));
    assertThat(identities.findByIssuerAndProviderSubject(ISSUER, second.subject())).isEmpty();
    assertThat(users.findByEmail(input.email()).orElseThrow().getId()).isEqualTo(first.id());
  }

  @Test
  void databaseRejectsBindingOneSubjectToTwoUsers() {
    var input = identity();
    service.resolveOrProvision(input);
    var other = users.saveAndFlush(new BankUser("Other Demo", email(), null));
    assertThatThrownBy(() -> identities.saveAndFlush(new ExternalIdentity(other, input)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void databaseRejectsTwoGoogleSubjectsForOneUser() {
    var input = identity();
    var user = users.findById(service.resolveOrProvision(input).id()).orElseThrow();
    assertThatThrownBy(() -> identities.saveAndFlush(new ExternalIdentity(user, identity())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void databaseEnforcesForeignKeyAndRequiredIdentityFields() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into"
                        + " auth_identities(id,user_id,provider,issuer,provider_subject,created_at)"
                        + " values(?,?,'google',?,'fixture',current_timestamp)",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    ISSUER))
        .isInstanceOf(DataIntegrityViolationException.class);
    var user = users.saveAndFlush(new BankUser("Constraint Demo", email(), null));
    for (String subject : Arrays.asList(null, "", "   ")) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "insert into"
                          + " auth_identities(id,user_id,provider,issuer,provider_subject,created_at)"
                          + " values(?,?,'google',?,?,current_timestamp)",
                      UUID.randomUUID(),
                      user.getId(),
                      ISSUER,
                      subject))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Test
  void subjectIdentityIsCaseSensitive() {
    String suffix = UUID.randomUUID().toString();
    var first = service.resolveOrProvision(identity("Subject" + suffix, email()));
    var second = service.resolveOrProvision(identity("subject" + suffix, email()));
    assertThat(second.id()).isNotEqualTo(first.id());
  }

  @Test
  void failureAfterUserInsertRollsBackUserAndIdentity() {
    var input = identity();
    doThrow(new DataIntegrityViolationException("Synthetic test insert failure"))
        .when(identities)
        .saveAndFlush(any(ExternalIdentity.class));
    conflict(() -> service.resolveOrProvision(input));
    assertThat(users.findByEmail(input.email())).isEmpty();
    assertThat(identities.findByIssuerAndProviderSubject(ISSUER, input.subject())).isEmpty();
  }

  @Test
  void concurrentFirstSignInsResolveOneCommittedUserWithoutOrphans() throws Exception {
    var input = identity();
    List<UUID> ids = parallel(12, () -> service.resolveOrProvision(input).id());
    assertThat(new HashSet<>(ids)).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from app_users where email=?", Long.class, input.email()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from auth_identities where issuer=? and provider_subject=?",
                Long.class,
                ISSUER,
                input.subject()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from auth_identities where user_id=?", Long.class, ids.getFirst()))
        .isEqualTo(1);
  }

  @Test
  void concurrentDifferentSubjectsWithSameEmailNeverLink() throws Exception {
    String email = email();
    List<Boolean> successes =
        parallel(
            8,
            () -> {
              try {
                service.resolveOrProvision(identity(UUID.randomUUID().toString(), email));
                return true;
              } catch (ApiException e) {
                assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                return false;
              }
            });
    assertThat(successes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    var user = users.findByEmail(email).orElseThrow();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from auth_identities where user_id=?", Long.class, user.getId()))
        .isEqualTo(1);
  }

  <T> List<T> parallel(int count, Callable<T> action) throws Exception {
    var ready = new CountDownLatch(count);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(count)) {
      List<Future<T>> results = new ArrayList<>();
      for (int i = 0; i < count; i++)
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Start timeout");
                  return action.call();
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<T> values = new ArrayList<>();
      for (var result : results) values.add(result.get(30, TimeUnit.SECONDS));
      return values;
    }
  }

  @Test
  void invalidInternalClaimsFailWithoutPersistence() {
    assertThatThrownBy(() -> service.resolveOrProvision(null)).isInstanceOf(ApiException.class);
    for (String issuer :
        Arrays.asList(null, "http://accounts.google.com", "https://attacker.example"))
      assertThatThrownBy(
              () -> ValidatedExternalIdentity.google(issuer, "subject", email(), true, "Demo"))
          .isInstanceOf(ApiException.class);
    for (String subject : Arrays.asList(null, "", " ", "x".repeat(256)))
      assertThatThrownBy(() -> identity(subject, email())).isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> ValidatedExternalIdentity.google(ISSUER, "subject", email(), false, "Demo"))
        .isInstanceOf(ApiException.class);
    for (String email : Arrays.asList(null, "", "invalid-email", "x".repeat(255) + "@example.test"))
      assertThatThrownBy(
              () -> service.resolveOrProvision(identity("invalid-" + UUID.randomUUID(), email)))
          .isInstanceOf(ApiException.class);
    for (String name : Arrays.asList(null, "", " ", "x".repeat(101)))
      assertThatThrownBy(
              () -> ValidatedExternalIdentity.google(ISSUER, "subject", email(), true, name))
          .isInstanceOf(ApiException.class);
  }

  @Test
  void googleOnlyPasswordLoginIsGenericAndDoesNotCreateAuthentication() throws Exception {
    var input = identity();
    var user = service.resolveOrProvision(input);
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("email", input.email(), "password", "Example-passphrase-21"))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Invalid email or password"));
    assertThat(users.findById(user.id()).orElseThrow().getPasswordHash()).isNull();
    mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void passwordRegistrationCannotAttachCredentialsToExternalUser() throws Exception {
    var input = identity();
    var user = passwords.me(service.resolveOrProvision(input).id());
    mvc.perform(
            post("/api/v1/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "fullName",
                            "Attempted Replacement",
                            "email",
                            input.email(),
                            "password",
                            "Example-passphrase-21"))))
        .andExpect(status().isConflict());
    assertThat(users.findById(user.id()).orElseThrow().getPasswordHash()).isNull();
    assertThat(passwords.me(user.id())).isEqualTo(user);
  }

  @Test
  void existingPasswordUserStillLogsInWithOriginalUuidAndHash() throws Exception {
    var email = email();
    var user = passwords.register("Password Demo", email, "Example-passphrase-21");
    var hash = users.findById(user.id()).orElseThrow().getPasswordHash();
    assertThat(encoder.matches("Example-passphrase-21", hash)).isTrue();
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("email", email, "password", "Example-passphrase-21"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(user.id().toString()));
    assertThat(users.findById(user.id()).orElseThrow().getPasswordHash()).isEqualTo(hash);
  }

  @Test
  void uniquenessFailureRecoversCommittedIdentityInFreshTransactionAndRemovesLosingUser() {
    var input = identity();
    UUID winner = UUID.randomUUID();
    String winnerEmail = email();
    var separate =
        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    separate.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    doAnswer(
            invocation -> {
              ExternalIdentity losing = invocation.getArgument(0);
              // A concurrent first login for the same subject can carry a changed email. Commit
              // that
              // winner independently, then force the loser's real PostgreSQL uniqueness violation.
              separate.executeWithoutResult(
                  status -> {
                    jdbc.update(
                        "insert into app_users(id,full_name,email,password_hash,created_at)"
                            + " values(?,'Committed Winner',?,null,current_timestamp)",
                        winner,
                        winnerEmail);
                    jdbc.update(
                        "insert into"
                            + " auth_identities(id,user_id,provider,issuer,provider_subject,created_at)"
                            + " values(?,?,'google',?,?,current_timestamp)",
                        UUID.randomUUID(),
                        winner,
                        ISSUER,
                        input.subject());
                  });
              jdbc.update(
                  "insert into"
                      + " auth_identities(id,user_id,provider,issuer,provider_subject,created_at)"
                      + " values(?,?,'google',?,?,current_timestamp)",
                  losing.getId(),
                  losing.getUser().getId(),
                  ISSUER,
                  input.subject());
              throw new AssertionError("PostgreSQL should reject duplicate identity");
            })
        .when(identities)
        .saveAndFlush(any(ExternalIdentity.class));
    var resolved = service.resolveOrProvision(input);
    assertThat(resolved.id()).isEqualTo(winner);
    assertThat(resolved.email()).isEqualTo(winnerEmail);
    assertThat(resolved.fullName()).isEqualTo("Committed Winner");
    assertThat(users.findByEmail(input.email())).isEmpty();
  }

  @Test
  void v10PreservesExistingPasswordUsersAndFinancialRecords() {
    String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var config =
        org.flywaydb.core.Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema);
    config.target("9").load().migrate();
    UUID user = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    UUID transaction = UUID.randomUUID();
    String email = email();
    String hash = encoder.encode("Example-passphrase-21");
    jdbc.update(
        "insert into "
            + schema
            + ".app_users(id,full_name,email,password_hash,created_at) values(?,'Existing"
            + " Demo',?,?,current_timestamp)",
        user,
        email,
        hash);
    jdbc.update(
        "insert into "
            + schema
            + ".bank_accounts(id,owner_id,account_number,balance,created_at)"
            + " values(?,?,'NB_UPGRADE_FIXTURE',100,current_timestamp)",
        account,
        user);
    jdbc.update(
        "insert into "
            + schema
            + ".banking_transactions(id,type,status,amount,destination_id,reference,created_at)"
            + " values(?,'DEPOSIT','SUCCESS',100,?,'UPGRADE_FIXTURE',current_timestamp)",
        transaction,
        account);
    config.target("10").load().migrate();
    assertThat(
            jdbc.queryForObject(
                "select password_hash from " + schema + ".app_users where id=?",
                String.class,
                user))
        .isEqualTo(hash);
    assertThat(
            jdbc.queryForObject(
                "select balance from " + schema + ".bank_accounts where id=?",
                java.math.BigDecimal.class,
                account))
        .isEqualByComparingTo("100");
    assertThat(
            jdbc.queryForObject(
                "select destination_id from " + schema + ".banking_transactions where id=?",
                UUID.class,
                transaction))
        .isEqualTo(account);
    assertThat(
            jdbc.queryForObject("select count(*) from " + schema + ".auth_identities", Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select is_nullable from information_schema.columns where table_schema=? and"
                    + " table_name='app_users' and column_name='password_hash'",
                String.class,
                schema))
        .isEqualTo("YES");
  }
}

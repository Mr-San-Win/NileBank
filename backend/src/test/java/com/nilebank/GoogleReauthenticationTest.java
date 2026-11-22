package com.nilebank;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.nilebank.auth.*;
import com.nilebank.user.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class GoogleReauthenticationTest {
  GoogleReauthentication service() {
    return new GoogleReauthentication(
        new MockEnvironment()
            .withProperty("nilebank.auth.google.enabled", "true")
            .withProperty("nilebank.auth.google.reauth-enabled", "true"),
        mock(UserRepository.class),
        mock(ExternalIdentityRepository.class));
  }

  @Test
  void recentProofIsBoundToOwnerSubjectAndActualAuthenticationAge() {
    var owner = UUID.randomUUID();
    Instant now = Instant.now();
    var intent =
        new GoogleReauthentication.Intent(
            owner, 0, "https://accounts.google.com", "subject", now, now.plusSeconds(300));
    var identity =
        ValidatedExternalIdentity.google(
            "https://accounts.google.com", "subject", "fictional@example.test", true, "Demo");
    var auth =
        UsernamePasswordAuthenticationToken.authenticated(
            owner.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    var proof = service().validate(intent, identity, now.minusSeconds(20), auth);
    assertThat(proof.owner()).isEqualTo(owner);
    assertThat(proof.expires()).isEqualTo(now.plusSeconds(280));
  }

  @Test
  void expiredIntentWrongOwnerAndPendingAuthorityFail() {
    var owner = UUID.randomUUID();
    Instant now = Instant.now();
    var identity =
        ValidatedExternalIdentity.google(
            "https://accounts.google.com", "subject", "fictional@example.test", true, "Demo");
    var auth =
        UsernamePasswordAuthenticationToken.authenticated(
            owner.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    var expired =
        new GoogleReauthentication.Intent(
            owner,
            0,
            "https://accounts.google.com",
            "subject",
            now.minusSeconds(400),
            now.minusSeconds(1));
    assertThatThrownBy(() -> service().validate(expired, identity, now, auth))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    var intent =
        new GoogleReauthentication.Intent(
            owner, 0, "https://accounts.google.com", "subject", now, now.plusSeconds(300));
    var pending =
        UsernamePasswordAuthenticationToken.authenticated(
            owner.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MFA_PENDING")));
    assertThatThrownBy(() -> service().validate(intent, identity, now, pending))
        .isInstanceOf(com.nilebank.common.ApiException.class);
    var other =
        UsernamePasswordAuthenticationToken.authenticated(
            UUID.randomUUID().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    assertThatThrownBy(() -> service().validate(intent, identity, now, other))
        .isInstanceOf(com.nilebank.common.ApiException.class);
  }

  @Test
  void cannotEnableWithoutGoogleAndDefaultsRemainOff() {
    assertThatThrownBy(
            () ->
                new GoogleReauthentication(
                    new MockEnvironment()
                        .withProperty("nilebank.auth.google.reauth-enabled", "true"),
                    mock(UserRepository.class),
                    mock(ExternalIdentityRepository.class)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            new GoogleReauthentication(
                    new MockEnvironment(),
                    mock(UserRepository.class),
                    mock(ExternalIdentityRepository.class))
                .supports(UUID.randomUUID()))
        .isFalse();
  }

  @Test
  void expiredWrongOwnerAndChangedCredentialProofCannotAuthorizeManagement() {
    var user = new BankUser("Proof Demo", "proof@example.test", null);
    Instant now = Instant.now();
    for (var proof :
        List.of(
            new GoogleReauthentication.Proof(
                UUID.randomUUID(),
                0,
                "https://accounts.google.com",
                "subject",
                now,
                now.plusSeconds(100)),
            new GoogleReauthentication.Proof(
                user.getId(),
                1,
                "https://accounts.google.com",
                "subject",
                now,
                now.plusSeconds(100)),
            new GoogleReauthentication.Proof(
                user.getId(),
                0,
                "https://accounts.google.com",
                "subject",
                now.minusSeconds(400),
                now.minusSeconds(1)))) assertThat(service().valid(user, proof)).isFalse();
  }
}

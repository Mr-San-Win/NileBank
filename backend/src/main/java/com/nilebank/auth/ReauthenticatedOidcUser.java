package com.nilebank.auth;

import java.util.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.*;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Server-only proof survives the OAuth filter replacing authentication details. */
public final class ReauthenticatedOidcUser implements OidcUser, VersionedPrincipal {
  private final OidcUser delegate;
  private final GoogleReauthentication.Proof proof;

  public ReauthenticatedOidcUser(OidcUser delegate, GoogleReauthentication.Proof proof) {
    this.delegate = delegate;
    this.proof = proof;
  }

  public GoogleReauthentication.Proof proof() {
    return proof;
  }

  @Override
  public String getName() {
    return delegate.getName();
  }

  @Override
  public Map<String, Object> getClaims() {
    return delegate.getClaims();
  }

  @Override
  public Map<String, Object> getAttributes() {
    return delegate.getAttributes();
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return delegate.getAuthorities();
  }

  @Override
  public OidcIdToken getIdToken() {
    return delegate.getIdToken();
  }

  @Override
  public OidcUserInfo getUserInfo() {
    return delegate.getUserInfo();
  }

  @Override
  public long credentialVersion() {
    return proof.version();
  }
}

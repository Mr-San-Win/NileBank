package com.nilebank.auth.mfa;

import com.nilebank.auth.VersionedPrincipal;
import java.util.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.*;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Keeps pending state outside Authentication.details, which the OAuth filter overwrites. */
public final class PendingOidcUser implements OidcUser, VersionedPrincipal {
  private final OidcUser delegate;
  private final MfaService.Pending pending;

  public PendingOidcUser(OidcUser delegate, MfaService.Pending pending) {
    this.delegate = delegate;
    this.pending = pending;
  }

  public MfaService.Pending pending() {
    return pending;
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
  public OidcIdToken getIdToken() {
    return delegate.getIdToken();
  }

  @Override
  public OidcUserInfo getUserInfo() {
    return delegate.getUserInfo();
  }

  @Override
  public String getName() {
    return delegate.getName();
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_MFA_PENDING"));
  }

  @Override
  public long credentialVersion() {
    return MfaService.version(pending.original());
  }
}

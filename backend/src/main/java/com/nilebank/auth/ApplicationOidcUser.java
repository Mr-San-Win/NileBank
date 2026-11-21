package com.nilebank.auth;

import java.util.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.*;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Keeps Google's subject intact while existing controllers see only the internal UUID name. */
public final class ApplicationOidcUser implements OidcUser {
  private final UUID userId;
  private final OidcUser delegate;

  public ApplicationOidcUser(UUID userId, OidcUser delegate) {
    this.userId = Objects.requireNonNull(userId);
    this.delegate = Objects.requireNonNull(delegate);
  }

  @Override
  public String getName() {
    return userId.toString();
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(
        new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("OIDC_USER"));
  }

  @Override
  public Map<String, Object> getAttributes() {
    return delegate.getAttributes();
  }

  @Override
  public Map<String, Object> getClaims() {
    return delegate.getClaims();
  }

  @Override
  public OidcUserInfo getUserInfo() {
    return delegate.getUserInfo();
  }

  @Override
  public OidcIdToken getIdToken() {
    return delegate.getIdToken();
  }
}

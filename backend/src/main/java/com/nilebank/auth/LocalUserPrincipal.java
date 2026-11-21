package com.nilebank.auth;

import java.util.*;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class LocalUserPrincipal
    implements UserDetails, CredentialsContainer, VersionedPrincipal {
  private final UUID id;
  private String password;
  private final long version;

  public LocalUserPrincipal(UUID id, String password, long version) {
    this.id = id;
    this.password = password;
    this.version = version;
  }

  @Override
  public String getUsername() {
    return id.toString();
  }

  @Override
  public String getPassword() {
    return password;
  }

  @Override
  public long credentialVersion() {
    return version;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @Override
  public void eraseCredentials() {
    password = null;
  }
}

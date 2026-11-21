package com.nilebank.auth;

/** Credential version is captured during authentication, never upgraded by a later request. */
public interface VersionedPrincipal {
  long credentialVersion();
}

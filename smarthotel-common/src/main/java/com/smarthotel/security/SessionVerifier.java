package com.smarthotel.security;
public interface SessionVerifier {
 /** null means invalid/revoked; unavailable dependencies must throw, never authenticate. */
 SessionIdentity verify(String token);
}

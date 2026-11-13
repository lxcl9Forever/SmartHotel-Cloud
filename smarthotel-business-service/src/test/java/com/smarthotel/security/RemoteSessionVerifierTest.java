package com.smarthotel.security;
import com.smarthotel.client.IdentityClient;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class RemoteSessionVerifierTest {
 private final IdentityClient client=mock(IdentityClient.class);
 private final RemoteSessionVerifier verifier=new RemoteSessionVerifier(client,"a-test-internal-token-at-least-32-chars");
 @Test void revokedTokenIsNotAuthenticated() {
  when(client.introspect(anyString(),any())).thenReturn(new IdentityClient.TokenResponse(false,null));
  assertThat(verifier.verify("revoked")).isNull();
 }
 @Test void outageNeverFallsBackToLocallyTrustingToken() {
  when(client.introspect(anyString(),any())).thenThrow(new IllegalStateException("down"));
  assertThatThrownBy(() -> verifier.verify("old-but-signed-token")).isInstanceOf(IllegalStateException.class);
 }
 @Test void validatedIdentityIsReturnedWithoutSharingPasswordHashes() {
  var identity=new SessionIdentity(42L,"guest","CUSTOMER");
  when(client.introspect(anyString(),any())).thenReturn(new IdentityClient.TokenResponse(true,identity));
  assertThat(verifier.verify("valid")).isEqualTo(identity);
 }
 @Test void malformedUpstreamResponseFailsClosed() {
  when(client.introspect(anyString(),any())).thenReturn(new IdentityClient.TokenResponse(true,null));
  assertThatThrownBy(() -> verifier.verify("valid")).isInstanceOf(IllegalStateException.class);
 }
}

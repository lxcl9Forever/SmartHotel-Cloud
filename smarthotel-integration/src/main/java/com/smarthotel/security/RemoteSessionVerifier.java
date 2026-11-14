package com.smarthotel.security;
import com.smarthotel.client.IdentityClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class RemoteSessionVerifier implements SessionVerifier {
 private final IdentityClient client;private final String token;
 public RemoteSessionVerifier(IdentityClient client,@Value("${smarthotel.internal.token}") String token) {
  if(token.length()<32) throw new IllegalArgumentException("INTERNAL_TOKEN must contain at least 32 characters");
  this.client=client;this.token=token;
 }
 public SessionIdentity verify(String bearer) {
  var result=client.introspect(token,new IdentityClient.TokenRequest(bearer));
  if(result==null) throw new IllegalStateException("Empty authentication response");
  if(!result.active()) return null;
  var id=result.identity();
  if(id==null || id.userId()==null || id.username()==null || !java.util.Set.of("ADMIN","STAFF","CUSTOMER").contains(id.role())) throw new IllegalStateException("Invalid authentication response");
  return id;
 }
}

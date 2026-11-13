package com.smarthotel.security;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/internal/v1/sessions")
public class InternalSessionController {
 private final LocalSessionVerifier verifier;
 private final byte[] secret;
 public InternalSessionController(LocalSessionVerifier verifier,@Value("${smarthotel.internal.token}") String token) {
  if(token.length()<32) throw new IllegalArgumentException("INTERNAL_TOKEN must contain at least 32 characters");
  this.verifier=verifier;this.secret=token.getBytes(StandardCharsets.UTF_8);
 }
 public record TokenRequest(String token) {}
 public record TokenResponse(boolean active,SessionIdentity identity) {}
 @PostMapping("/introspect") public TokenResponse introspect(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@RequestBody TokenRequest request) {
  if(!MessageDigest.isEqual(secret,key.getBytes(StandardCharsets.UTF_8))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  if(request.token()==null || request.token().length()>8192) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  var identity=verifier.verify(request.token());
  return new TokenResponse(identity!=null,identity);
 }
}

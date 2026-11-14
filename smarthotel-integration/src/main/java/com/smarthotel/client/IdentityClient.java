package com.smarthotel.client;
import com.smarthotel.security.SessionIdentity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
@FeignClient(name="smarthotel-user-service",url="${smarthotel.services.user-url:}")
public interface IdentityClient {
 record TokenRequest(String token) {}
 record TokenResponse(boolean active,SessionIdentity identity) {}
 @PostMapping("/internal/v1/sessions/introspect")
 TokenResponse introspect(@RequestHeader("X-Internal-Token") String key,@RequestBody TokenRequest request);
}

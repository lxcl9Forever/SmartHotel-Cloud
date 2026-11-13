package com.smarthotel.security;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
class JwtAuthenticationFilterTest {
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 @Test void unavailableAuthReturns503WithoutCallingProtectedHandler() throws Exception {
  var filter=new JwtAuthenticationFilter(token -> {throw new IllegalStateException("offline");});
  var req=new MockHttpServletRequest("GET","/api/v1/bookings");req.addHeader("Authorization","Bearer example");
  var res=new MockHttpServletResponse();
  filter.doFilter(req,res,(request,response)-> {throw new AssertionError("must not continue");});
  assertThat(res.getStatus()).isEqualTo(503);
  assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
 }
 @Test void forgedIdentityHeadersCannotAuthenticateWithoutBearer() throws Exception {
  var filter=new JwtAuthenticationFilter(token -> {throw new AssertionError("not called");});
  var req=new MockHttpServletRequest("GET","/api/v1/bookings");req.addHeader("X-User-Id","1");req.addHeader("X-User-Role","ADMIN");
  filter.doFilter(req,new MockHttpServletResponse(),(request,response)->{});
  assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
 }
}

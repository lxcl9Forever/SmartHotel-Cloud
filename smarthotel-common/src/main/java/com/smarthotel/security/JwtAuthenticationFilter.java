package com.smarthotel.security;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component @RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
 private final SessionVerifier verifier;
 @Override protected boolean shouldNotFilter(HttpServletRequest request) {
  return request.getRequestURI().startsWith("/internal/") || request.getRequestURI().startsWith("/actuator/");
 }
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
  String header=request.getHeader("Authorization");
  if(header!=null && header.startsWith("Bearer ")) {
   SessionIdentity identity;
   try { identity=verifier.verify(header.substring(7)); }
   catch(RuntimeException unavailable) {
    response.setStatus(503); response.setContentType(MediaType.APPLICATION_JSON_VALUE); response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"code\":503,\"message\":\"认证服务暂时不可用\",\"data\":null}"); return;
   }
   if(identity!=null) {
    var auth=new UsernamePasswordAuthenticationToken(identity.username(),identity.userId(),List.of(new SimpleGrantedAuthority("ROLE_"+identity.role())));
    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(auth);
   }
  }
  chain.doFilter(request,response);
 }
}

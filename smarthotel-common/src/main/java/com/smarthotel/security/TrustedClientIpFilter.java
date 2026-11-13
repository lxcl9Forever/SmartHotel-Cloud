package com.smarthotel.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Only the gateway's authenticated client-address header may affect rate-limit keys. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TrustedClientIpFilter extends OncePerRequestFilter {
    private final byte[] secret;
    public TrustedClientIpFilter(@Value("${smarthotel.internal.token}") String token) {
        if (token.length() < 32) throw new IllegalArgumentException("INTERNAL_TOKEN must contain at least 32 characters");
        secret = token.getBytes(StandardCharsets.UTF_8);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader("X-Gateway-Token");
        String address = request.getHeader("X-Cloud-Client-IP");
        if (key != null && address != null && address.length() <= 45
                && address.matches("[0-9a-fA-F:.]+")
                && MessageDigest.isEqual(secret, key.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public String getRemoteAddr() { return address; }
            }, response);
        } else {
            chain.doFilter(request, response);
        }
    }
}

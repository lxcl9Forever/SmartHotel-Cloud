package com.smarthotel.gateway;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.springframework.beans.factory.annotation.Value;
/** Client-supplied identity/internal headers are never trusted. Bearer token is verified by services. */
@Component
public class BoundaryFilter implements GlobalFilter,Ordered {
 private final String gatewayToken;
 public BoundaryFilter(@Value("${smarthotel.internal.token}") String token) {
  if(token.length()<32) throw new IllegalArgumentException("INTERNAL_TOKEN must contain at least 32 characters");
  this.gatewayToken=token;
 }
 public int getOrder(){return -1000;}
 public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain) {
  String path=exchange.getRequest().getURI().getPath();
  if(path.startsWith("/internal/") || path.startsWith("/api/v1/auth/loadtest")) {
   exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);return exchange.getResponse().setComplete();
  }
  var request=exchange.getRequest().mutate().headers(headers -> {
   headers.remove("X-Internal-Token");headers.remove("X-User-Id");headers.remove("X-User-Role");headers.remove("X-User-Name");
   headers.remove("Forwarded");headers.remove("X-Forwarded-For");headers.remove("X-Real-IP");
   headers.remove("X-Gateway-Token");headers.remove("X-Cloud-Client-IP");
   if(exchange.getRequest().getRemoteAddress()!=null) {
    headers.set("X-Cloud-Client-IP",exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
    headers.set("X-Gateway-Token",gatewayToken);
   }
  }).build();
  return chain.filter(exchange.mutate().request(request).build());
 }
}

package com.smarthotel.gateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.assertThat;

/** Real gateway HTTP round trips to two independent HTTP upstreams. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="smarthotel.internal.token=gateway-test-internal-token-at-least-32-chars")
class GatewayRoutingTest {
 static final HttpServer USER=server("user"), BUSINESS=server("business"), HOTEL=server("hotel");
 @LocalServerPort int port;
 @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
  registry.add("smarthotel.services.user-uri",()->"http://127.0.0.1:"+USER.getAddress().getPort());
  registry.add("smarthotel.services.hotel-uri",()->"http://127.0.0.1:"+HOTEL.getAddress().getPort());
  registry.add("smarthotel.services.business-uri",()->"http://127.0.0.1:"+BUSINESS.getAddress().getPort());
 }
 static HttpServer server(String name){
  try {
   var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/",exchange->{
    String spoof=exchange.getRequestHeaders().getFirst("X-User-Role");
    String secret=exchange.getRequestHeaders().getFirst("X-Internal-Token");
    byte[] body=(name+":"+exchange.getRequestURI().getPath()+":"+spoof+":"+secret).getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
   });server.start();return server;
  } catch(Exception e){throw new IllegalStateException(e);}
 }
 @AfterAll static void stop(){USER.stop(0);BUSINESS.stop(0);HOTEL.stop(0);}
 WebClient client(){return WebClient.create("http://127.0.0.1:"+port);}
 @Test void userAndBusinessPathsGoToCorrectService(){
  for(String path:new String[]{"/api/v1/auth/captcha","/api/v1/user/me","/api/v1/customer/profile","/api/v1/customer/saved-guests/1","/api/v1/admin/users","/api/v1/admin/employees/1"}){
   assertThat(client().get().uri(path).retrieve().bodyToMono(String.class).block()).startsWith("user:"+path);
  }
  for(String path:new String[]{"/api/v1/bookings","/api/v1/admin/payments","/api/v1/customer/stays"}){
   assertThat(client().get().uri(path).retrieve().bodyToMono(String.class).block()).startsWith("business:"+path);
  }
 }
 @Test void hotelReadsHaveSeparateUpstream() {
  for(String path:new String[]{"/api/v1/room-types","/api/v1/room-types/available","/api/v1/hotel","/api/v1/hotel/room-types/1"})
   assertThat(client().get().uri(path).retrieve().bodyToMono(String.class).block()).startsWith("hotel:"+path);
  assertThat(client().get().uri("/api/v1/hotel/notice").retrieve().bodyToMono(String.class).block()).startsWith("business:");
 }
 @Test void internalAndLoadtestEndpointsAreNotPublished(){
  for(String path:new String[]{"/internal/v1/sessions/introspect","/api/v1/auth/loadtest/register"}){
   assertThat(client().get().uri(path).exchangeToMono(res->reactor.core.publisher.Mono.just(res.statusCode().value())).block()).isEqualTo(404);
  }
 }
 @Test void clientSuppliedPrivilegeHeadersAreRemoved(){
  assertThat(client().get().uri("/api/v1/bookings").header("X-User-Role","ADMIN").header("X-Internal-Token","spoof")
   .retrieve().bodyToMono(String.class).block()).isEqualTo("business:/api/v1/bookings:null:null");
 }
}

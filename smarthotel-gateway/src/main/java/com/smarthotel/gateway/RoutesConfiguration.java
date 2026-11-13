package com.smarthotel.gateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;
@Configuration
public class RoutesConfiguration {
 @Bean RouteLocator routes(RouteLocatorBuilder builder,@Value("${smarthotel.services.user-uri}") String user,@Value("${smarthotel.services.business-uri}") String business,@Value("${smarthotel.services.hotel-uri}") String hotel) {
  return builder.routes()
   .route("identity",r -> r.path("/api/v1/auth/**","/api/v1/user/**","/api/v1/customer/profile","/api/v1/customer/profile/**","/api/v1/customer/saved-guests","/api/v1/customer/saved-guests/**","/api/v1/admin/users","/api/v1/admin/users/**","/api/v1/admin/employees","/api/v1/admin/employees/**").uri(user))
   .route("hotel-read",r -> r.method("GET").and().path("/api/v1/room-types","/api/v1/room-types/**","/api/v1/hotel","/api/v1/hotel/room-types/**").uri(hotel))
   .route("business",r -> r.path("/api/**").uri(business)).build();
 }
}

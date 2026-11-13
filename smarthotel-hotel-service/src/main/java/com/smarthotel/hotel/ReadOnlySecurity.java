package com.smarthotel.hotel;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
@Configuration public class ReadOnlySecurity {
 @Bean SecurityFilterChain hotelSecurity(HttpSecurity http) throws Exception {
  return http.csrf(c->c.disable()).authorizeHttpRequests(a->a
   .requestMatchers(HttpMethod.GET,"/actuator/health","/api/v1/room-types","/api/v1/room-types/**","/api/v1/hotel","/api/v1/hotel/room-types/**").permitAll()
   .requestMatchers(HttpMethod.GET,"/internal/v1/hotel-admin/room-types","/internal/v1/hotel-admin/inventories","/internal/v1/hotel-admin/prices").permitAll()
   .requestMatchers(HttpMethod.PUT,"/internal/v1/hotel-admin/prices").permitAll()
   .requestMatchers(HttpMethod.POST,"/internal/v1/hotel-catalog/types","/internal/v1/hotel-catalog/prices").permitAll()
   .requestMatchers(HttpMethod.GET,"/internal/v1/hotel-catalog/active-types","/internal/v1/hotel-catalog/hotel","/internal/v1/hotel-catalog/images/*").permitAll()
   .requestMatchers(HttpMethod.GET,"/internal/v1/hotel/capacity/*").permitAll()
   .anyRequest().denyAll()).build();
 }
}

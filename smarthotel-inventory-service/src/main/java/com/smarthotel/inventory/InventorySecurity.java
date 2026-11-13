package com.smarthotel.inventory;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
@Configuration public class InventorySecurity {
 @Bean SecurityFilterChain inventorySecurityChain(HttpSecurity http)throws Exception{return http.csrf(c->c.disable()).authorizeHttpRequests(a->a.requestMatchers(HttpMethod.GET,"/actuator/health").permitAll().requestMatchers(HttpMethod.POST,"/internal/v1/inventory/**").permitAll().anyRequest().denyAll()).build();}
}

package com.smarthotel.gateway;
import org.springframework.beans.factory.annotation.Value;import org.springframework.cloud.gateway.route.RouteLocator;import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;import org.springframework.context.annotation.*;
@Configuration public class RoutesConfiguration {
 @Bean RouteLocator routes(RouteLocatorBuilder builder,@Value("${smarthotel.services.user-uri}")String user,@Value("${smarthotel.services.order-uri}")String order,@Value("${smarthotel.services.hotel-uri}")String hotel,@Value("${smarthotel.services.review-uri}")String review,@Value("${smarthotel.services.marketing-uri}")String marketing,@Value("${smarthotel.services.payment-uri}")String payment,@Value("${smarthotel.services.stay-uri}")String stay,@Value("${smarthotel.services.statistics-uri}")String statistics){return builder.routes()
 .route("identity",route->route.path("/api/v1/auth/**","/api/v1/user/**","/api/v1/customer/profile","/api/v1/customer/profile/**","/api/v1/customer/saved-guests","/api/v1/customer/saved-guests/**","/api/v1/admin/users","/api/v1/admin/users/**","/api/v1/admin/employees","/api/v1/admin/employees/**").uri(user))
 .route("review",route->route.path("/api/v1/customer/reviews/**","/api/v1/admin/reviews/**").uri(review))
 .route("marketing",route->route.path("/api/v1/admin/promotion/**","/api/v1/admin/daily-deals/**","/api/v1/daily-deals","/api/v1/customer/checkin","/api/v1/admin/checkin/**").uri(marketing))
 .route("payment",route->route.path("/api/v1/payment/**","/api/v1/admin/payments","/api/v1/admin/payments/**","/api/v1/customer/refund/**","/api/v1/admin/refund/**","/api/v1/bookings/*/mock-pay").uri(payment))
 .route("stay",route->route.path("/api/v1/staff/**","/api/v1/customer/stay/**").uri(stay))
 .route("statistics",route->route.path("/api/v1/admin/statistics/**").uri(statistics))
 .route("hotel-read",route->route.method("GET").and().path("/api/v1/room-types","/api/v1/room-types/**","/api/v1/hotel","/api/v1/hotel/room-types/**").uri(hotel))
 .route("ai",route->route.path("/api/v1/customer/ai/**").metadata("response-timeout",45000L).metadata("connect-timeout",2000).uri(order))
 .route("order",route->route.path("/api/**").uri(order)).build();}
}

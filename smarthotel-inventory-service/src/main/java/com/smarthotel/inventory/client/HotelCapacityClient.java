package com.smarthotel.inventory.client;
import org.springframework.cloud.openfeign.FeignClient;import org.springframework.web.bind.annotation.*;
@FeignClient(name="smarthotel-hotel-service",contextId="hotelCapacity",url="${smarthotel.services.hotel-url:}",configuration=ReadTransportConfiguration.class)
public interface HotelCapacityClient {@GetMapping("/internal/v1/hotel/capacity/{id}") Integer capacity(@RequestHeader("X-Internal-Token") String token,@PathVariable Long id);}

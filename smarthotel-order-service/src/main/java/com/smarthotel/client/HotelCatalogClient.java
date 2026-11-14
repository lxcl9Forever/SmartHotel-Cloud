package com.smarthotel.client;
import com.smarthotel.contract.HotelCatalogContract.*;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.cloud.openfeign.FeignClient;
@FeignClient(name="smarthotel-hotel-service",contextId="hotelCatalog",url="${smarthotel.services.hotel-url:}",configuration=InventoryTransportConfiguration.class)
public interface HotelCatalogClient {
 @PostMapping("/internal/v1/hotel-catalog/types") List<TypeSnapshot> types(@RequestHeader("X-Internal-Token") String token,@RequestBody TypeQuery query);
 @GetMapping("/internal/v1/hotel-catalog/active-types") List<TypeSnapshot> active(@RequestHeader("X-Internal-Token") String token);
 @PostMapping("/internal/v1/hotel-catalog/prices") List<PriceSnapshot> prices(@RequestHeader("X-Internal-Token") String token,@RequestBody PriceQuery query);
 @GetMapping("/internal/v1/hotel-catalog/hotel") HotelReply hotel(@RequestHeader("X-Internal-Token") String token);
 @GetMapping("/internal/v1/hotel-catalog/images/{id}") List<ImageSnapshot> images(@RequestHeader("X-Internal-Token") String token,@PathVariable Long id);
}

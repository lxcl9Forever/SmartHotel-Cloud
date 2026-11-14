package com.smarthotel.client;
import java.util.List;
import java.time.LocalDate;
import com.smarthotel.contract.HotelCatalogContract.TypeSnapshot;
import com.smarthotel.contract.HotelCatalogContract.PriceSnapshot;
import com.smarthotel.dto.admin.UpdatePriceRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.cloud.openfeign.FeignClient;
@FeignClient(name="smarthotel-hotel-service",contextId="hotelAdmin",url="${smarthotel.services.hotel-url:}",configuration=InventoryTransportConfiguration.class)
public interface HotelAdminClient {
 @GetMapping("/internal/v1/hotel-admin/room-types") List<TypeSnapshot> listRoomTypes(@RequestHeader(value="X-Internal-Token",defaultValue="") String key);
 @GetMapping("/internal/v1/hotel-admin/prices") List<PriceSnapshot> listPrices(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@RequestParam Long roomTypeId,@RequestParam LocalDate startDate,@RequestParam LocalDate endDate);
 @PutMapping("/internal/v1/hotel-admin/prices") void updatePrice(@RequestHeader("X-Internal-Token") String key,@RequestBody UpdatePriceRequest request);
}

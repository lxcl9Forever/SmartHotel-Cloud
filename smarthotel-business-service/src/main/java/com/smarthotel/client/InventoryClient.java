package com.smarthotel.client;
import com.smarthotel.contract.InventoryTccContract.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
@FeignClient(name="smarthotel-inventory-service",contextId="inventoryOwner",url="${smarthotel.services.inventory-url:}",configuration=InventoryTransportConfiguration.class)
public interface InventoryClient {
 @PostMapping("/internal/v1/inventory/day") java.util.List<com.smarthotel.contract.InventoryReadContract.Snapshot> day(@RequestHeader("X-Internal-Token") String token,@RequestBody java.time.LocalDate day);

 @PostMapping("/internal/v1/inventory/query") java.util.List<com.smarthotel.contract.InventoryReadContract.Snapshot> query(@RequestHeader("X-Internal-Token") String token,@RequestBody com.smarthotel.contract.InventoryReadContract.Query query);
 @PostMapping("/internal/v1/inventory/prepare") Reply prepare(@RequestHeader("X-Internal-Token") String token,@RequestBody Command command);
 @PostMapping("/internal/v1/inventory/decide") Reply decide(@RequestHeader("X-Internal-Token") String token,@RequestBody Decision decision);
 @PostMapping("/internal/v1/inventory/set-total") void setTotal(@RequestHeader("X-Internal-Token") String token,@RequestBody SetTotal command);
 @PostMapping("/internal/v1/inventory/enqueue") void enqueue(@RequestHeader("X-Internal-Token") String token,@RequestBody Range range);
 @PostMapping("/internal/v1/inventory/synchronize") Boolean synchronize(@RequestHeader("X-Internal-Token") String token,@RequestBody Range range);
 @PostMapping("/internal/v1/inventory/rebuild") void rebuild(@RequestHeader("X-Internal-Token") String token);
}

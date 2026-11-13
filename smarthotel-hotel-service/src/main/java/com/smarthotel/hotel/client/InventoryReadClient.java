package com.smarthotel.hotel.client;
import com.smarthotel.contract.InventoryReadContract.Query;import com.smarthotel.contract.InventoryReadContract.Snapshot;import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;import org.springframework.web.bind.annotation.*;
@FeignClient(name="smarthotel-inventory-service",contextId="hotelInventoryRead",url="${smarthotel.services.inventory-url:}",configuration=ReadTransportConfiguration.class)
public interface InventoryReadClient {@PostMapping("/internal/v1/inventory/query") List<Snapshot> query(@RequestHeader("X-Internal-Token") String token,@RequestBody Query query);}

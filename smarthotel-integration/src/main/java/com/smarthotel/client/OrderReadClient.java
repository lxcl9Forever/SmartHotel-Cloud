package com.smarthotel.client;
import com.smarthotel.contract.order.OrderSnapshot;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
@FeignClient(name="smarthotel-order-service",contextId="orderRead",url="${smarthotel.services.order-url:}")
public interface OrderReadClient {
 @GetMapping("/internal/v1/orders/{id}") OrderSnapshot find(@RequestHeader("X-Internal-Token") String token,@PathVariable("id") Long id);
 @GetMapping("/internal/v1/orders/{id}/customer-view") com.smarthotel.vo.BookingOrderVO view(@RequestHeader("X-Internal-Token")String token,@PathVariable("id")Long id,@RequestParam("username")String username);
}

package com.smarthotel.client;
import java.math.BigDecimal;import org.springframework.cloud.openfeign.FeignClient;import org.springframework.web.bind.annotation.*;
@FeignClient(name="smarthotel-review-service",url="${smarthotel.services.review-url:}") public interface ReviewClient {
 record Rating(BigDecimal average){}
 @GetMapping("/internal/v1/reviews/ratings/{id}") Rating rating(@RequestHeader("X-Internal-Token") String token,@PathVariable("id") Long id);
}

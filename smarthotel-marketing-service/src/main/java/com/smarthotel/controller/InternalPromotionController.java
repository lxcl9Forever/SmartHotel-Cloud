package com.smarthotel.controller;
import com.smarthotel.integration.InternalGuard;
import com.smarthotel.service.PromotionService;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
@RestController public class InternalPromotionController {
  private final InternalGuard guard;
  private final PromotionService promotions;
  public InternalPromotionController(InternalGuard guard,PromotionService promotions) {
    this.guard=guard;
    this.promotions=promotions;
  }
  @PostMapping("/internal/v1/marketing/price") public PromotionService.Price quote(@RequestHeader(value="X-Internal-Token",defaultValue="")String token,@RequestBody BigDecimal amount) {
    guard.require(token);
    if(amount.signum()<0)throw new IllegalArgumentException("Invalid amount");
    return promotions.calculate(amount);
  }
}

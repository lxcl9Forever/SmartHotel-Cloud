package com.smarthotel.controller;
import com.smarthotel.service.MockPaymentService;import com.smarthotel.common.result.Result;import com.smarthotel.vo.BookingOrderVO;import org.springframework.web.bind.annotation.*;import org.springframework.security.core.Authentication;
@RestController public class MockPaymentController {
 private final MockPaymentService payments;private final com.smarthotel.service.OrderDirectory orders;public MockPaymentController(MockPaymentService payments,com.smarthotel.service.OrderDirectory orders){this.payments=payments;this.orders=orders;}
 @PostMapping("/api/v1/bookings/{orderId}/mock-pay") public Result<BookingOrderVO> pay(@PathVariable Long orderId,Authentication authentication){payments.pay(authentication.getName(),orderId);return Result.success(orders.view(authentication.getName(),orderId));}
}

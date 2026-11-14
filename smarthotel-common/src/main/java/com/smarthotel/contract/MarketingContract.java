package com.smarthotel.contract;
import java.math.BigDecimal;import com.smarthotel.dto.booking.CreateBookingRequest;
public final class MarketingContract {
 private MarketingContract(){}
 public record Prepare(String operationId,Long customerId,CreateBookingRequest request,BigDecimal originalAmount){}
 public record Quote(String operationId,Long promotionId,BigDecimal originalAmount,BigDecimal discountAmount,BigDecimal payableAmount,BigDecimal couponDiscountAmount){}
 public record Decision(String operationId,String decision,Long orderId){}
 public record Phase(String phase){}
}

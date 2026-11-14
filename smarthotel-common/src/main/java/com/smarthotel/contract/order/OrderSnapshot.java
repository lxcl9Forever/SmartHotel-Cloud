package com.smarthotel.contract.order;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class OrderSnapshot {

    private Long orderId;

    private String orderNo;
    private String activeWorkflowId;
    private String marketingOperationId;

    private Long customerId;

    private Long roomTypeId;

    private Integer roomCount;

    private Integer guestCount;

    private LocalDate checkInDate;

    private LocalDate checkOutDate;

    private Integer nights;

    private BigDecimal totalAmount;
    private BigDecimal originalAmount;
    private BigDecimal discountAmount;
    private Long promotionId;
    private Long dailyDealId;
    private Long couponId;
    private BigDecimal couponDiscountAmount;

    private String contactName;

    private String contactPhone;

    private String orderStatus;

    private String paymentStatus;

    private LocalDateTime expireTime;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

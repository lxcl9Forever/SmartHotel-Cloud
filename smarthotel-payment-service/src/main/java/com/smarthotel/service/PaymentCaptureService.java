package com.smarthotel.service;

import com.smarthotel.contract.DirectoryAccount;
import com.smarthotel.contract.DirectoryCustomer;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.request.AlipayTradeFastpayRefundQueryRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarthotel.config.AlipayConfig;
import com.smarthotel.entity.BookingOrder;
import com.smarthotel.entity.Payment;
import com.smarthotel.entity.RefundRecord;
import com.smarthotel.model.inventory.RoomInventory;
import com.smarthotel.exception.BusinessException;
import com.smarthotel.mapper.BookingOrderMapper;
import com.smarthotel.mapper.PaymentMapper;
import com.smarthotel.vo.AlipayPageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCaptureService {
    private final BookingOrderMapper bookingOrderMapper;
    private final InventoryCommands inventoryCommands;
    private final PaymentMapper paymentMapper;
    private final DailyCheckinService dailyCheckinService;
    @Transactional
    public boolean capture(String paymentNo, String tradeNo, BigDecimal notifiedAmount) {
        Payment found = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getPaymentNo, paymentNo)
                .eq(Payment::getPaymentMethod, "ALIPAY"));
        if (found == null) return false;
        // Keep the same lock order as checkout and timeout cancellation: order, then payment.
        BookingOrder order = bookingOrderMapper.selectOne(new LambdaQueryWrapper<BookingOrder>()
                .eq(BookingOrder::getOrderId, found.getOrderId()).last("FOR UPDATE"));
        if (order == null) return false;
        Payment payment = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getPaymentId, found.getPaymentId()).last("FOR UPDATE"));
        if (payment == null) return false;
        if (payment.getAmount().compareTo(notifiedAmount) != 0) return false;
        if ("SUCCESS".equals(payment.getPaymentStatus())) {
            return tradeNo.equals(payment.getThirdPartyNo());
        }
        if (!"PENDING".equals(payment.getPaymentStatus())) return false;

        if (!"WAIT_PAY".equals(order.getOrderStatus())
                || !"UNPAID".equals(order.getPaymentStatus())
                || payment.getAmount().compareTo(order.getTotalAmount()) != 0) {
            log.error("Paid Alipay trade {} needs manual reconciliation for order {}",
                    tradeNo, payment.getOrderId());
            return false;
        }

        inventoryCommands.confirm(order);
        payment.setPaymentStatus("SUCCESS");
        payment.setThirdPartyNo(tradeNo);
        payment.setPaidTime(LocalDateTime.now());
        paymentMapper.updateById(payment);
        order.setPaymentStatus("PAID");
        order.setOrderStatus("CONFIRMED");
        bookingOrderMapper.updateById(order);
        dailyCheckinService.markUsed(order.getOrderId());
        return true;
    }

}

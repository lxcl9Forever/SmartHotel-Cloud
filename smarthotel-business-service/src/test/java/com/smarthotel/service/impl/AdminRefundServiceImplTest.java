package com.smarthotel.service.impl;

import com.smarthotel.entity.BookingOrder;
import com.smarthotel.entity.Payment;
import com.smarthotel.entity.RefundRecord;
import com.smarthotel.entity.RefundPaymentRecord;
import com.smarthotel.model.inventory.RoomInventory;
import com.smarthotel.exception.BusinessException;
import com.smarthotel.service.AlipayService;
import com.smarthotel.mapper.BookingOrderMapper;
import com.smarthotel.mapper.PaymentMapper;
import com.smarthotel.mapper.RefundRecordMapper;
import com.smarthotel.mapper.RefundPaymentRecordMapper;
import com.smarthotel.service.InventoryReadGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminRefundServiceImplTest {

    private final RefundRecordMapper refunds = mock(RefundRecordMapper.class);
    private final BookingOrderMapper orders = mock(BookingOrderMapper.class);
    private final PaymentMapper payments = mock(PaymentMapper.class);
    private final InventoryReadGateway inventories = mock(InventoryReadGateway.class);
    private final RefundPaymentRecordMapper refundPayments = mock(RefundPaymentRecordMapper.class);
    private final AlipayService alipayService = mock(AlipayService.class);
    private final com.smarthotel.service.InventoryCommands commands=mock(com.smarthotel.service.InventoryCommands.class);
    private AdminRefundServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminRefundServiceImpl(refunds, orders, payments, inventories, refundPayments, alipayService, mock(com.smarthotel.service.UserDirectory.class), commands);
    }

    @Test
    void approveMarksRefundAndOrderRefunded() {
        BookingOrder order = pendingOrder();
        stubPendingRefund(order);
        Payment payment = new Payment();
        payment.setAmount(new BigDecimal("1588.00"));
        payment.setPaymentMethod("MOCK");
        payment.setPaymentStatus("SUCCESS");
        when(payments.selectList(any())).thenReturn(List.of(payment));
        RoomInventory inventory = new RoomInventory();
        inventory.setSoldQty(1);
        when(inventories.query(any(),any(),any())).thenReturn(List.of(inventory));
        when(refundPayments.insert(any(RefundPaymentRecord.class))).thenReturn(1);
        when(refunds.updateStatus(5L, "APPROVED")).thenReturn(1);
        when(orders.updateById(order)).thenReturn(1);

        service.approve(5L);

        assertEquals("REFUNDED", order.getOrderStatus());
        assertEquals("REFUNDED", order.getPaymentStatus());
        verify(commands).refund(order);
        verify(refunds).updateStatus(5L, "APPROVED");
        verify(refundPayments).insert(argThat(record ->
                record.getRefundId().equals(5L)
                        && record.getRefundAmount().compareTo(new BigDecimal("1588.00")) == 0
                        && "PENDING".equals(record.getInventorySyncStatus())));
    }

    @Test
    void rejectRestoresPaidState() {
        BookingOrder order = pendingOrder();
        stubPendingRefund(order);
        when(refunds.updateStatus(5L, "REJECTED")).thenReturn(1);
        when(orders.updateById(order)).thenReturn(1);

        service.reject(5L);

        assertEquals("CONFIRMED", order.getOrderStatus());
        assertEquals("PAID", order.getPaymentStatus());
        verify(refunds).updateStatus(5L, "REJECTED");
        verifyNoInteractions(payments, inventories, refundPayments);
    }

    @Test
    void repeatedReviewReturnsConflictWithoutChangingOrder() {
        RefundRecord refund = pendingRefund();
        refund.setRefundStatus("APPROVED");
        when(refunds.selectById(5L)).thenReturn(refund);
        when(refunds.selectByIdForUpdate(5L)).thenReturn(refund);
        when(orders.selectOne(any())).thenReturn(pendingOrder());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.reject(5L));

        assertEquals(409, error.getCode());
        verify(refunds, never()).updateStatus(any(), any());
        verify(orders, never()).updateById(any(BookingOrder.class));
    }

    @Test
    void approvalWithoutSoldInventoryCannotCreateRefundLedger() {
        stubPendingRefund(pendingOrder());
        Payment payment = new Payment();
        payment.setAmount(new BigDecimal("1588.00"));
        payment.setPaymentMethod("MOCK");
        payment.setPaymentStatus("SUCCESS");
        when(payments.selectList(any())).thenReturn(List.of(payment));
        RoomInventory inventory = new RoomInventory();
        inventory.setSoldQty(0);
        when(inventories.query(any(),any(),any())).thenReturn(List.of(inventory));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.approve(5L));

        assertEquals(409, error.getCode());
        verify(refundPayments, never()).insert(any());
        verify(refunds, never()).updateStatus(any(), any());
    }

    @Test
    void approveAlipayPaymentUsesOriginalTradeAndRecordsAlipayRefund() {
        BookingOrder order = pendingOrder();
        stubPendingRefund(order);
        Payment payment = alipayPayment();
        when(payments.selectList(any())).thenReturn(List.of(payment));
        RoomInventory inventory = new RoomInventory();
        inventory.setSoldQty(1);
        when(inventories.query(any(),any(),any())).thenReturn(List.of(inventory));
        when(refundPayments.insert(any(RefundPaymentRecord.class))).thenReturn(1);
        when(refunds.updateStatus(5L, "APPROVED")).thenReturn(1);
        when(orders.updateById(order)).thenReturn(1);

        service.approve(5L);

        verify(alipayService).refund(eq(payment), any(RefundRecord.class), eq("RF000000000000000000000000000005"));
        verify(refundPayments).insert(argThat(record ->
                "ALIPAY".equals(record.getRefundMethod())
                        && "RF000000000000000000000000000005".equals(record.getRefundNo())
                        && "SUCCESS".equals(record.getRefundStatus())));
        assertEquals("REFUNDED", order.getPaymentStatus());
        verify(commands).refund(order);
    }

    @Test
    void failedAlipayRefundDoesNotChangeInventoryOrReviewStatus() {
        BookingOrder order = pendingOrder();
        stubPendingRefund(order);
        Payment payment = alipayPayment();
        when(payments.selectList(any())).thenReturn(List.of(payment));
        RoomInventory inventory = new RoomInventory();
        inventory.setSoldQty(1);
        when(inventories.query(any(),any(),any())).thenReturn(List.of(inventory));
        doThrow(new BusinessException(502, "支付宝退款未成功"))
                .when(alipayService).refund(eq(payment), any(RefundRecord.class), any());

        assertThrows(BusinessException.class, () -> service.approve(5L));

        assertEquals(1, inventory.getSoldQty());
        assertEquals("REFUNDING", order.getPaymentStatus());
        verify(inventories).query(any(),any(),any());
        verify(refundPayments, never()).insert(any());
        verify(refunds, never()).updateStatus(any(), any());
    }

    private Payment alipayPayment() {
        Payment payment = new Payment();
        payment.setOrderId(30L);
        payment.setPaymentNo("merchant-payment-30");
        payment.setThirdPartyNo("alipay-trade-30");
        payment.setPaymentMethod("ALIPAY");
        payment.setPaymentStatus("SUCCESS");
        payment.setAmount(new BigDecimal("1588.00"));
        return payment;
    }

    private void stubPendingRefund(BookingOrder order) {
        RefundRecord refund = pendingRefund();
        when(refunds.selectById(5L)).thenReturn(refund);
        when(refunds.selectByIdForUpdate(5L)).thenReturn(refund);
        when(orders.selectOne(any())).thenReturn(order);
    }

    private RefundRecord pendingRefund() {
        RefundRecord refund = new RefundRecord();
        refund.setRefundId(5L);
        refund.setOrderId(30L);
        refund.setRefundStatus("PENDING");
        refund.setRefundAmount(new BigDecimal("1588.00"));
        return refund;
    }

    private BookingOrder pendingOrder() {
        BookingOrder order = new BookingOrder();
        order.setOrderId(30L);
        order.setOrderStatus("CONFIRMED");
        order.setPaymentStatus("REFUNDING");
        order.setRoomTypeId(14L);
        order.setRoomCount(1);
        order.setNights(1);
        order.setCheckInDate(LocalDate.of(2026, 10, 13));
        order.setCheckOutDate(LocalDate.of(2026, 10, 14));
        return order;
    }
}

package com.smarthotel.service;
import com.smarthotel.config.AlipayConfig;
import com.smarthotel.contract.order.OrderSnapshot;
import com.smarthotel.entity.Payment;
import com.smarthotel.entity.RefundRecord;
import com.smarthotel.mapper.PaymentMapper;
import com.smarthotel.exception.BusinessException;
import com.alipay.api.response.AlipayTradeQueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class AlipaySettlementServiceTest {
 final AlipayService channel=mock(AlipayService.class);final PaymentMapper payments=mock(PaymentMapper.class);final OrderDirectory orders=mock(OrderDirectory.class);final PaymentCaptureService captures=mock(PaymentCaptureService.class);final PaymentAuditStore audits=mock(PaymentAuditStore.class);final JdbcTemplate jdbc=mock(JdbcTemplate.class);
 final AlipaySettlementService service=new AlipaySettlementService(channel,payments,orders,captures,audits,jdbc,new AlipayConfig());
 Payment payment;OrderSnapshot order;
 @BeforeEach void setup(){payment=new Payment();payment.setPaymentId(1L);payment.setPaymentNo("SYNTHETIC_PAY");payment.setOrderId(9L);payment.setPaymentMethod("ALIPAY");payment.setPaymentStatus("PENDING");payment.setAmount(new BigDecimal("688.00"));order=new OrderSnapshot();order.setOrderId(9L);order.setOrderStatus("WAIT_PAY");order.setPaymentStatus("UNPAID");when(jdbc.queryForList(anyString(),eq(Long.class),eq("SYNTHETIC_PAY"))).thenReturn(List.of(1L));when(payments.selectById(1L)).thenReturn(payment);when(orders.find(9L)).thenReturn(order);when(audits.find(1L)).thenReturn(Map.of());}
 @Test void verifiedReceiptUsesOrderCoordinator(){when(captures.capture(anyString(),anyString(),any())).thenReturn(true);assertEquals("PAID",service.accept("SYNTHETIC_PAY","ALI_TRADE",new BigDecimal("688")));verify(captures).capture("SYNTHETIC_PAY","ALI_TRADE",new BigDecimal("688"));}
 @Test void cancelledOrderReceiptIsRecordedWithoutResurrectingOrder(){order.setOrderStatus("CANCELLED");assertEquals("NEEDS_REVIEW",service.accept("SYNTHETIC_PAY","ALI_TRADE",new BigDecimal("688")));verifyNoInteractions(captures);verify(audits).record(eq(payment),eq("NEEDS_REVIEW"),eq("TRADE_SUCCESS"),eq("ALI_TRADE"),any(),anyString(),eq(true));}
 @Test void mismatchedAmountNeverClaimsOrder(){assertThrows(BusinessException.class,()->service.accept("SYNTHETIC_PAY","ALI_TRADE",new BigDecimal("1")));verifyNoInteractions(captures,orders);verify(audits).record(eq(payment),eq("MISMATCH"),anyString(),anyString(),any(),anyString(),eq(true));}
 @Test void alreadySuccessfulReceiptIsIdempotent(){payment.setPaymentStatus("SUCCESS");payment.setThirdPartyNo("ALI_TRADE");assertEquals("PAID",service.accept("SYNTHETIC_PAY","ALI_TRADE",new BigDecimal("688")));verifyNoInteractions(captures,orders);}
 @Test void unknownTradeStaysPending(){var response=new AlipayTradeQueryResponse();response.setCode("40004");response.setSubCode("ACQ.TRADE_NOT_EXIST");when(channel.queryTrade(payment)).thenReturn(response);assertEquals("PENDING",service.reconcile(1L));verifyNoInteractions(captures);}
 @Test void paidOrderCannotUseExceptionRefund(){order.setOrderStatus("CONFIRMED");assertThrows(BusinessException.class,()->service.refundException(1L));verify(audits,never()).requestRefund(anyLong());}
 @Test void uncertainExceptionRefundKeepsDurableRequestForSameNumberRetry(){order.setOrderStatus("CANCELLED");when(audits.requestRefund(1L)).thenReturn("REFUND_REQUESTED");when(audits.find(1L)).thenReturn(Map.of("trade_no","ALI_TRADE"));doThrow(new BusinessException(502,"unknown")).doNothing().when(channel).refund(eq(payment),any(RefundRecord.class),eq("EXRFSYNTHETIC_PAY"));assertThrows(BusinessException.class,()->service.refundException(1L));verify(audits,never()).refunded(1L);assertEquals("REFUNDED",service.refundException(1L));verify(channel,times(2)).refund(eq(payment),any(),eq("EXRFSYNTHETIC_PAY"));verify(audits).refunded(1L);}
 @Test void alreadyRefundedRequestDoesNotMoveFundsAgain(){order.setOrderStatus("CANCELLED");when(audits.requestRefund(1L)).thenReturn("REFUNDED");assertEquals("REFUNDED",service.refundException(1L));verifyNoInteractions(channel);}
}

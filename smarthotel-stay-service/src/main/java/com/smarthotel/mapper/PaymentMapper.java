package com.smarthotel.mapper;
import com.smarthotel.entity.Payment;import com.smarthotel.integration.workflow.WorkflowContext;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Component;
@Component public class PaymentMapper {
 private final JdbcTemplate jdbc;public PaymentMapper(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public int insert(Payment payment){return jdbc.update("INSERT INTO stay_charge(operation_id,order_id,payment_no,amount,paid_time) VALUES(?,?,?,?,?)",WorkflowContext.operationId(),payment.getOrderId(),payment.getPaymentNo(),payment.getAmount(),payment.getPaidTime());}
}

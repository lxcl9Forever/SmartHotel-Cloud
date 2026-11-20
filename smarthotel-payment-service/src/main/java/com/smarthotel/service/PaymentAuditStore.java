package com.smarthotel.service;
import com.smarthotel.entity.Payment;
import com.smarthotel.mapper.PaymentMapper;
import com.smarthotel.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
@Service public class PaymentAuditStore {
 private final JdbcTemplate jdbc;private final PaymentMapper payments;
 public PaymentAuditStore(JdbcTemplate jdbc,PaymentMapper payments){this.jdbc=jdbc;this.payments=payments;}
 public void record(Payment p,String state,String gateway,String trade,BigDecimal amount,String note,boolean attention){
  jdbc.update("INSERT INTO payment_channel_audit(payment_id,order_id,state,gateway_status,trade_no,received_amount,note,needs_attention,last_checked_time) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE state=CASE WHEN state IN ('REFUND_REQUESTED','REFUNDED') THEN state ELSE VALUES(state) END,gateway_status=VALUES(gateway_status),trade_no=CASE WHEN state IN ('REFUND_REQUESTED','REFUNDED') THEN trade_no ELSE COALESCE(VALUES(trade_no),trade_no) END,received_amount=CASE WHEN state IN ('REFUND_REQUESTED','REFUNDED') THEN received_amount ELSE COALESCE(VALUES(received_amount),received_amount) END,note=CASE WHEN state IN ('REFUND_REQUESTED','REFUNDED') THEN note ELSE VALUES(note) END,needs_attention=CASE WHEN state IN ('REFUND_REQUESTED','REFUNDED') THEN needs_attention ELSE VALUES(needs_attention) END,last_checked_time=CURRENT_TIMESTAMP",p.getPaymentId(),p.getOrderId(),state,gateway,trade,amount,note,attention?1:0);
 }
 public List<Map<String,Object>> list(){return jdbc.queryForList("SELECT a.payment_id AS paymentId,a.order_id AS orderId,p.payment_no AS paymentNo,o.order_no AS orderNo,p.amount AS expectedAmount,a.received_amount AS receivedAmount,a.state,a.gateway_status AS gatewayStatus,a.trade_no AS tradeNo,a.note,a.needs_attention AS needsAttention,a.last_checked_time AS lastCheckedTime,a.refunded_time AS refundedTime FROM payment_channel_audit a JOIN payment p ON p.payment_id=a.payment_id LEFT JOIN order_snapshot o ON o.order_id=a.order_id ORDER BY a.needs_attention DESC,a.last_checked_time DESC LIMIT 100");}
 public Map<String,Object> find(long id){var rows=jdbc.queryForList("SELECT * FROM payment_channel_audit WHERE payment_id=?",id);return rows.isEmpty()?Map.of():rows.get(0);}
 @Transactional public void close(Payment p){jdbc.update("UPDATE payment SET payment_status='FAILED' WHERE payment_id=? AND payment_status='PENDING'",p.getPaymentId());}
 @Transactional public String requestRefund(long id){
  var rows=jdbc.queryForList("SELECT * FROM payment_channel_audit WHERE payment_id=? FOR UPDATE",id);
  if(rows.isEmpty())throw new BusinessException(409,"没有可处理的渠道核对记录");var audit=rows.get(0);String state=audit.get("state").toString();
  if("REFUNDED".equals(state))return "REFUNDED";
  if(!Set.of("NEEDS_REVIEW","REFUND_REQUESTED").contains(state)||audit.get("trade_no")==null||audit.get("received_amount")==null)throw new BusinessException(409,"该记录不是已验证的异常收款");
  var p=payments.selectById(id);if(p==null||p.getAmount().compareTo(new BigDecimal(audit.get("received_amount").toString()))!=0)throw new BusinessException(409,"渠道金额与账本不一致，需人工核对");
  jdbc.update("UPDATE payment_channel_audit SET state='REFUND_REQUESTED',needs_attention=1,note='异常收款退款处理中，网络异常时按同一退款号恢复' WHERE payment_id=?",id);return "REFUND_REQUESTED";
 }
 @Transactional public void refunded(long id){
  jdbc.queryForList("SELECT payment_id FROM payment_channel_audit WHERE payment_id=? FOR UPDATE",id);
  jdbc.update("UPDATE payment SET payment_status='REFUNDED',third_party_no=(SELECT trade_no FROM payment_channel_audit WHERE payment_id=?) WHERE payment_id=?",id,id);
  jdbc.update("UPDATE payment_channel_audit SET state='REFUNDED',needs_attention=0,note='异常收款已通过渠道确认退回',refunded_time=CURRENT_TIMESTAMP,last_checked_time=CURRENT_TIMESTAMP WHERE payment_id=?",id);
 }
}

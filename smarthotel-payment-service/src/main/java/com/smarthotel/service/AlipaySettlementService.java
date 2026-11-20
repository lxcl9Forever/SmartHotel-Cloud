package com.smarthotel.service;
import com.smarthotel.entity.Payment;
import com.smarthotel.entity.RefundRecord;
import com.smarthotel.mapper.PaymentMapper;
import com.smarthotel.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import com.smarthotel.config.AlipayConfig;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
@lombok.extern.slf4j.Slf4j
@Service public class AlipaySettlementService {
 private final AlipayService channel;private final PaymentMapper payments;private final OrderDirectory orders;private final PaymentCaptureService captures;private final PaymentAuditStore audits;private final JdbcTemplate jdbc;private final AlipayConfig config;private final AtomicBoolean running=new AtomicBoolean();
 public AlipaySettlementService(AlipayService channel,PaymentMapper payments,OrderDirectory orders,PaymentCaptureService captures,PaymentAuditStore audits,JdbcTemplate jdbc,AlipayConfig config){this.channel=channel;this.payments=payments;this.orders=orders;this.captures=captures;this.audits=audits;this.jdbc=jdbc;this.config=config;}
 public String accept(String paymentNo,String tradeNo,BigDecimal amount){
  var list=jdbc.queryForList("SELECT payment_id FROM payment WHERE payment_no=? AND payment_method='ALIPAY'",Long.class,paymentNo);if(list.isEmpty())throw new BusinessException(404,"支付记录不存在");
  var p=payments.selectById(list.get(0));
  if(tradeNo==null||tradeNo.isBlank()||amount==null||p.getAmount().compareTo(amount)!=0){audits.record(p,"MISMATCH","TRADE_SUCCESS",tradeNo,amount,"渠道金额或流水与本地记录不符，禁止自动入账",true);throw new BusinessException(409,"支付金额或流水核对失败");}
  if("REFUNDED".equals(p.getPaymentStatus()))return "REFUNDED";
  if("SUCCESS".equals(p.getPaymentStatus())){if(!tradeNo.equals(p.getThirdPartyNo()))throw new BusinessException(409,"支付流水冲突");return "PAID";}
  var source=orders.find(p.getOrderId());
  if(!"WAIT_PAY".equals(source.getOrderStatus())||!"UNPAID".equals(source.getPaymentStatus())){
   audits.record(p,"NEEDS_REVIEW","TRADE_SUCCESS",tradeNo,amount,"订单已取消、关闭或已有付款；收款已验证，需管理员核对退回",true);return "NEEDS_REVIEW";
  }
  captures.capture(paymentNo,tradeNo,amount);audits.record(p,"MATCHED","TRADE_SUCCESS",tradeNo,amount,"渠道付款与订单已确认",false);return "PAID";
 }
 public String reconcile(long id){
  var p=payments.selectById(id);if(p==null||!"ALIPAY".equals(p.getPaymentMethod()))throw new BusinessException(404,"支付宝支付记录不存在");
  var audit=audits.find(id);if("REFUND_REQUESTED".equals(audit.get("state")))return refundException(id);if("REFUNDED".equals(audit.get("state")))return "REFUNDED";
  var response=channel.queryTrade(p);
  if(!response.isSuccess()){
   if("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())){audits.record(p,"PENDING","NOT_CREATED",null,null,"尚未进入收银台或交易尚未创建",false);return "PENDING";}
   audits.record(p,"ERROR","QUERY_FAILED",null,null,"渠道查询未成功，请检查沙箱配置或稍后重试",true);throw new BusinessException(502,"支付宝交易查询失败，请检查配置或稍后重试");
  }
  if(!p.getPaymentNo().equals(response.getOutTradeNo()))throw new BusinessException(502,"渠道返回的支付订单号不匹配");
  String status=response.getTradeStatus();
  if(status==null||status.isBlank())throw new BusinessException(502,"渠道返回的交易状态无效");
  if(Set.of("TRADE_SUCCESS","TRADE_FINISHED").contains(status)){
   BigDecimal amount;try{amount=new BigDecimal(response.getTotalAmount());}catch(RuntimeException invalid){throw new BusinessException(502,"渠道返回的金额无效");}
   return accept(p.getPaymentNo(),response.getTradeNo(),amount);
  }
  if("TRADE_CLOSED".equals(status)){audits.close(p);audits.record(p,"CLOSED",status,response.getTradeNo(),null,"支付宝交易已关闭",false);return "CLOSED";}
  var source=orders.find(p.getOrderId());
  if(Set.of("CANCELLED","CLOSED").contains(source.getOrderStatus())){
   if(channel.closeTrade(p)){audits.close(p);audits.record(p,"CLOSED","TRADE_CLOSED",null,null,"本地订单关闭，渠道待付款交易已关闭",false);return "CLOSED";}
   audits.record(p,"ERROR",status,null,null,"关单遇到并发付款或渠道异常，下次重新查询",true);throw new BusinessException(503,"渠道关单结果待核对，请稍后重新查询");
  }
  audits.record(p,"PENDING",status,null,null,"等待买家付款",false);return "PENDING";
 }
 public String refundException(long id){
  var p=payments.selectById(id);if(p==null||!"ALIPAY".equals(p.getPaymentMethod()))throw new BusinessException(404,"支付记录不存在");
  var source=orders.find(p.getOrderId());if(!Set.of("CANCELLED","CLOSED").contains(source.getOrderStatus()))throw new BusinessException(409,"仅能在已取消或关闭的订单上退回异常收款，正常订单请走退款审核");
  if("REFUNDED".equals(audits.requestRefund(id)))return "REFUNDED";
  var audit=audits.find(id);p.setPaymentStatus("SUCCESS");p.setThirdPartyNo(audit.get("trade_no").toString());
  var refund=new RefundRecord();refund.setRefundId(id);refund.setRefundAmount(p.getAmount());refund.setRefundReason("已关闭订单异常收款退回");
  channel.refund(p,refund,"EXRF"+p.getPaymentNo());audits.refunded(id);return "REFUNDED";
 }
 @Scheduled(fixedDelayString="${smarthotel.payment.reconcile-delay-ms:60000}") public void recover(){
  if(!config.isEnabled()||!running.compareAndSet(false,true))return;
  try{
   var ids=jdbc.queryForList("SELECT p.payment_id FROM payment p LEFT JOIN payment_channel_audit a ON a.payment_id=p.payment_id WHERE p.payment_method='ALIPAY' AND (a.state='REFUND_REQUESTED' OR (p.payment_status='PENDING' AND p.create_time>TIMESTAMPADD(DAY,-7,CURRENT_TIMESTAMP) AND (a.state IS NULL OR a.state NOT IN ('NEEDS_REVIEW','MISMATCH','REFUNDED')))) ORDER BY CASE WHEN a.last_checked_time IS NULL THEN 0 ELSE 1 END,a.last_checked_time,p.payment_id LIMIT 10",Long.class);
   for(Long id:ids)try{reconcile(id);}catch(RuntimeException deferred){log.warn("Channel reconciliation deferred for payment {} ({})",id,deferred.getClass().getSimpleName());}
  }finally{running.set(false);}
 }
}

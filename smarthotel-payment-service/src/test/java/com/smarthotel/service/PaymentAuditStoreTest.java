package com.smarthotel.service;
import com.smarthotel.entity.Payment;
import com.smarthotel.mapper.PaymentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PaymentAuditStoreTest {
 @Test void refundIntentSurvivesNewStoreAndLateQueryCannotEraseIt(){
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");var jdbc=new JdbcTemplate(ds);
  jdbc.execute("CREATE TABLE payment(payment_id BIGINT PRIMARY KEY,payment_status VARCHAR(32),third_party_no VARCHAR(64))");
  jdbc.execute("CREATE TABLE payment_channel_audit(payment_id BIGINT PRIMARY KEY,order_id BIGINT,state VARCHAR(32),gateway_status VARCHAR(40),trade_no VARCHAR(64),received_amount DECIMAL(12,2),note VARCHAR(500),needs_attention INT,last_checked_time TIMESTAMP,refunded_time TIMESTAMP)");
  jdbc.update("INSERT INTO payment VALUES(1,'PENDING',NULL)");
  var p=new Payment();p.setPaymentId(1L);p.setOrderId(9L);p.setAmount(new BigDecimal("688.00"));var mapper=mock(PaymentMapper.class);when(mapper.selectById(1L)).thenReturn(p);
  var store=new PaymentAuditStore(jdbc,mapper);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  store.record(p,"NEEDS_REVIEW","TRADE_SUCCESS","verified-trade",p.getAmount(),"verified receipt",true);
  tx.execute(s->store.requestRefund(1L));
  var restarted=new PaymentAuditStore(jdbc,mapper);
  assertEquals("REFUND_REQUESTED",restarted.find(1L).get("state"));
  restarted.record(p,"PENDING","WAIT_BUYER_PAY","late-trade",BigDecimal.ONE,"late query",false);
  assertEquals("REFUND_REQUESTED",restarted.find(1L).get("state"));assertEquals(1,((Number)restarted.find(1L).get("needs_attention")).intValue());
  assertEquals("verified-trade",restarted.find(1L).get("trade_no"));assertEquals(0,p.getAmount().compareTo(new BigDecimal(restarted.find(1L).get("received_amount").toString())));
  tx.execute(s->{restarted.refunded(1L);return null;});
  restarted.record(p,"NEEDS_REVIEW","TRADE_SUCCESS","verified-trade",p.getAmount(),"duplicate callback",true);
  assertEquals("REFUNDED",restarted.find(1L).get("state"));assertEquals(0,((Number)restarted.find(1L).get("needs_attention")).intValue());assertEquals("REFUNDED",jdbc.queryForObject("SELECT payment_status FROM payment WHERE payment_id=1",String.class));
 }
}

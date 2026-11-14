package com.smarthotel.service;
import com.smarthotel.integration.events.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
@Component public class PaymentProjectionListener implements DomainEventListener {
  private final JdbcTemplate jdbc;
  public PaymentProjectionListener(JdbcTemplate jdbc) {
    this.jdbc=jdbc;
  }
  public void accepted(DomainEvent event) {
    if(event.deleted())return;
    var row=event.row();
    if("stay".equals(event.source())&&"stay_charge".equals(event.entityType())) {
      jdbc.update("INSERT INTO payment(order_id,payment_no,payment_method,amount,payment_status,paid_time,origin_operation_id) VALUES(?,?,'MOCK',?,'SUCCESS',?,?) ON DUPLICATE KEY UPDATE origin_operation_id=origin_operation_id",row.get("order_id"),row.get("payment_no"),row.get("amount"),row.get("paid_time"),"STAY:"+event.aggregateId());
    }
    else if("order".equals(event.source())&&"booking_order".equals(event.entityType())&&"PAID".equals(row.get("payment_status"))&&new BigDecimal(row.get("total_amount").toString()).signum()==0) {
      jdbc.update("INSERT INTO payment(order_id,payment_no,payment_method,amount,payment_status,paid_time,origin_operation_id) VALUES(?,?,'COUPON',0,'SUCCESS',CURRENT_TIMESTAMP,?) ON DUPLICATE KEY UPDATE origin_operation_id=origin_operation_id",row.get("order_id"),"COUPON-"+row.get("order_id"),"COUPON:"+row.get("order_id"));
    }
  }
}

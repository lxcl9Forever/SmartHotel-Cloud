package com.smarthotel.controller;
import static com.smarthotel.contract.MarketingContract.*;
import com.smarthotel.service.*;
import com.smarthotel.integration.*;
import com.smarthotel.integration.events.*;
import com.smarthotel.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;
@RestController public class MarketingReservationController implements DomainEventListener {
  private final JdbcTemplate jdbc;
  private final InternalGuard guard;
  private final PromotionService promotions;
  private final DailyCheckinService coupons;
  private final DailyDealService deals;
  private final ObjectMapper json;
  public MarketingReservationController(JdbcTemplate jdbc,InternalGuard guard,PromotionService promotions,DailyCheckinService coupons,DailyDealService deals,ObjectMapper json) {
    this.jdbc=jdbc;
    this.guard=guard;
    this.promotions=promotions;
    this.coupons=coupons;
    this.deals=deals;
    this.json=json;
  }
  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    }
    catch(Exception e) {
      throw new IllegalStateException(e);
    }
  }
  @PostMapping("/internal/v1/marketing/reservations/prepare") @Transactional public Quote prepare(@RequestHeader(value="X-Internal-Token",defaultValue="")String token,@RequestBody Prepare command) {
    guard.require(token);
    if(command.operationId()==null||!command.operationId().matches("[0-9a-f-]{36}")||command.customerId()==null||command.originalAmount()==null||command.originalAmount().signum()<0||command.request()==null)throw new BusinessException(400,"营销预留参数无效");
    String payload=encode(command);
    jdbc.update("INSERT IGNORE INTO marketing_reservation(operation_id,customer_id,room_count,state,released,request_json) VALUES(?,?,?,'NEW',0,?)",command.operationId(),command.customerId(),command.request().getRoomCount(),payload);
    var row=jdbc.queryForMap("SELECT * FROM marketing_reservation WHERE operation_id=? FOR UPDATE",command.operationId());
    String phase=row.get("state").toString();
    if(!"NEW".equals(phase)) {
      if("ABORTED".equals(phase))throw new BusinessException(409,"营销预留已撤销");
      if(!payload.equals(row.get("request_json")))throw new BusinessException(409,"营销幂等标识内容冲突");
      try {
        return json.readValue(row.get("quote_json").toString(),Quote.class);
      }
      catch(Exception e) {
        throw new IllegalStateException(e);
      }
    }
    var request=command.request();
    var deal=request.getDailyDealId()==null?null:deals.lockForBooking(request);
    var price=deal==null?promotions.calculate(command.originalAmount()):deals.price(deal,command.originalAmount());
    BigDecimal coupon=deal==null?coupons.lockDiscount(command.customerId(),request.getCouponId(),price.payableAmount()):BigDecimal.ZERO;
    var quote=new Quote(command.operationId(),price.promotionId(),price.originalAmount(),price.discountAmount(),price.payableAmount(),coupon);
    if(request.getCouponId()!=null) {
      coupons.reserve(command.customerId(),request.getCouponId(),0);
      jdbc.update("UPDATE customer_coupon SET order_id=NULL,reservation_id=? WHERE id=? AND customer_id=?",command.operationId(),request.getCouponId(),command.customerId());
    }
    jdbc.update("UPDATE marketing_reservation SET state='PREPARED',deal_id=?,coupon_id=?,quote_json=? WHERE operation_id=?",request.getDailyDealId(),request.getCouponId(),encode(quote),command.operationId());
    return quote;
  }
  @PostMapping("/internal/v1/marketing/reservations/decide") @Transactional public Phase decide(@RequestHeader(value="X-Internal-Token",defaultValue="")String token,@RequestBody Decision decision) {
    guard.require(token);
    if(!Set.of("COMMIT","ABORT").contains(decision.decision()))throw new BusinessException(400,"无效营销决议");
    if("ABORT".equals(decision.decision()))jdbc.update("INSERT IGNORE INTO marketing_reservation(operation_id,customer_id,room_count,state,released,request_json) VALUES(?,0,0,'ABORTED',1,'{}')",decision.operationId());
    var row=jdbc.queryForMap("SELECT * FROM marketing_reservation WHERE operation_id=? FOR UPDATE",decision.operationId());
    String phase=row.get("state").toString();
    if("COMMIT".equals(decision.decision())) {
      if("ABORTED".equals(phase))throw new BusinessException(409,"营销预留已撤销");
      if(decision.orderId()==null)throw new BusinessException(400,"提交营销预留需要订单标识");
      if(row.get("order_id")!=null&&((Number)row.get("order_id")).longValue()!=decision.orderId())throw new BusinessException(409,"营销预留已绑定其他订单");
      jdbc.update("UPDATE marketing_reservation SET state='COMMITTED',order_id=? WHERE operation_id=?",decision.orderId(),decision.operationId());
      jdbc.update("UPDATE customer_coupon SET order_id=? WHERE reservation_id=? AND status='RESERVED'",decision.orderId(),decision.operationId());
      return new Phase("COMMITTED");
    }
    if("COMMITTED".equals(phase))return new Phase("COMMITTED");
    jdbc.update("UPDATE marketing_reservation SET state='ABORTED',released=1 WHERE operation_id=?",decision.operationId());
    jdbc.update("UPDATE customer_coupon SET status=IF(expire_time>CURRENT_TIMESTAMP,'AVAILABLE','EXPIRED'),order_id=NULL,reservation_id=NULL WHERE reservation_id=? AND status='RESERVED'",decision.operationId());
    return new Phase("ABORTED");
  }
  @Override public void accepted(DomainEvent event) {
    if(!"order".equals(event.source())||!"booking_order".equals(event.entityType())||event.deleted())return;
    var order=event.row();
    Object operation=order.get("marketing_operation_id");
    if(operation==null)return;
    var found=jdbc.queryForList("SELECT * FROM marketing_reservation WHERE operation_id=? FOR UPDATE",operation);
    if(found.isEmpty())throw new BusinessException(503,"订单对应的营销预留尚不可见");
    if("ABORTED".equals(found.get(0).get("state")))throw new BusinessException(409,"已撤销的营销预留不能被订单提交");
    Long orderId=((Number)order.get("order_id")).longValue();
    jdbc.update("UPDATE marketing_reservation SET state='COMMITTED',order_id=? WHERE operation_id=?",orderId,operation);
    String state=String.valueOf(order.get("order_status")),paid=String.valueOf(order.get("payment_status"));
    jdbc.update("UPDATE customer_coupon SET order_id=? WHERE reservation_id=? AND status='RESERVED'",orderId,operation);
    if(Set.of("CANCELLED","CLOSED","REFUNDED").contains(state)) {
      jdbc.update("UPDATE marketing_reservation SET released=1 WHERE operation_id=?",operation);
      if(!"REFUNDED".equals(state))coupons.release(orderId);
    }
    else if(Set.of("PAID","REFUNDING","REFUNDED").contains(paid))coupons.markUsed(orderId);
  }
}

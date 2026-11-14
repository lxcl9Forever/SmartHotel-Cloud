package com.smarthotel.controller;
import com.smarthotel.integration.*;
import com.smarthotel.integration.events.DomainEvents;
import com.smarthotel.integration.workflow.WorkflowContract.*;
import com.smarthotel.exception.BusinessException;
import com.smarthotel.service.InventoryCommands;
import com.smarthotel.mapper.BookingOrderMapper;
import com.smarthotel.mapper.BookingGuestMapper;
import com.smarthotel.entity.BookingGuest;
import com.smarthotel.vo.BookingGuestVO;
import com.smarthotel.contract.order.OrderSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
@RestController public class OrderWorkflowController {
  private final JdbcTemplate jdbc;
  private final InternalGuard guard;
  private final BookingOrderMapper orders;
  private final BookingGuestMapper guests;
  private final InventoryCommands inventory;
  private final ObjectMapper json;
  public OrderWorkflowController(JdbcTemplate jdbc,InternalGuard guard,BookingOrderMapper orders,BookingGuestMapper guests,InventoryCommands inventory,ObjectMapper json) {
    this.jdbc=jdbc;
    this.guard=guard;
    this.orders=orders;
    this.guests=guests;
    this.inventory=inventory;
    this.json=json;
  }
  private void valid(Claim c) {
    if(c==null||c.domain()==null||c.action()==null||c.operationId()==null||!c.operationId().matches("[0-9a-f-]{36}")||c.orderId()==null||c.requestHash()==null||!c.requestHash().matches("[0-9a-f]{64}"))throw new BusinessException(400,"协调操作标识无效");
    var allowed=c.domain().equals("payment")?Set.of("MOCK_PAY","PAYMENT_CAPTURE","REFUND_APPLY","REFUND_APPROVE","REFUND_REJECT"):c.domain().equals("stay")?Set.of("CHECK_IN","CHECK_OUT","EXTEND","NO_SHOW","COMPLETE"):Set.of();
    if(!allowed.contains(c.action()))throw new BusinessException(403,"领域不允许执行此订单操作");
  }
  private void state(Claim c,com.smarthotel.entity.BookingOrder o) {
    boolean ok=switch(c.action()) {
      case "MOCK_PAY","PAYMENT_CAPTURE"->"WAIT_PAY".equals(o.getOrderStatus())&&"UNPAID".equals(o.getPaymentStatus())&&o.getExpireTime()!=null&&o.getExpireTime().isAfter(LocalDateTime.now());
      case "REFUND_APPLY"->"CONFIRMED".equals(o.getOrderStatus())&&"PAID".equals(o.getPaymentStatus());
      case "REFUND_APPROVE","REFUND_REJECT"->"CONFIRMED".equals(o.getOrderStatus())&&"REFUNDING".equals(o.getPaymentStatus());
      case "CHECK_IN","NO_SHOW"->"CONFIRMED".equals(o.getOrderStatus())&&"PAID".equals(o.getPaymentStatus());
      case "CHECK_OUT","EXTEND"->"CHECKED_IN".equals(o.getOrderStatus())&&"PAID".equals(o.getPaymentStatus());
      case "COMPLETE"->"CHECKED_OUT".equals(o.getOrderStatus());
      default->false;
    }
    ;
    if(!ok)throw new BusinessException(409,"当前订单状态不允许该操作");
  }
  private com.smarthotel.entity.BookingOrder lock(long id) {
    jdbc.queryForList("SELECT order_id FROM booking_order WHERE order_id=? FOR UPDATE",id);
    var o=orders.selectById(id);
    if(o==null)throw new BusinessException(404,"订单不存在");
    return o;
  }
  @PostMapping("/internal/v1/order-workflows/claim") @Transactional public Claimed claim(@RequestHeader(value="X-Internal-Token",defaultValue="")String token,@RequestBody Claim c) {
    guard.require(token);
    valid(c);
    var o=lock(c.orderId());
    var previous=jdbc.queryForList("SELECT * FROM order_workflow WHERE operation_id=? FOR UPDATE",c.operationId());
    if(!previous.isEmpty()) {
      var op=previous.get(0);
      if(!c.domain().equals(op.get("domain_name"))||!c.action().equals(op.get("action"))||!c.requestHash().equals(op.get("request_hash"))||((Number)op.get("order_id")).longValue()!=c.orderId())throw new BusinessException(409,"协调操作内容冲突");
      if(!"ACTIVE".equals(op.get("status")))throw new BusinessException(409,"协调操作已结束");
    }
    else {
      String active=jdbc.queryForObject("SELECT active_workflow_id FROM booking_order WHERE order_id=?",String.class,c.orderId());
      if(active!=null)throw new BusinessException(409,"订单有待决操作，请稍后查询");
      state(c,o);
      jdbc.update("INSERT INTO order_workflow(operation_id,domain_name,action,order_id,request_hash,status) VALUES(?,?,?,?,?,'ACTIVE')",c.operationId(),c.domain(),c.action(),c.orderId(),c.requestHash());
      jdbc.update("UPDATE booking_order SET active_workflow_id=? WHERE order_id=?",c.operationId(),c.orderId());
    }
    var snapshot=new OrderSnapshot();
    BeanUtils.copyProperties(o,snapshot);
    List<BookingGuestVO> list=new ArrayList<>();
    if("stay".equals(c.domain()))for(var g:guests.selectList(new LambdaQueryWrapper<BookingGuest>().eq(BookingGuest::getOrderId,c.orderId()))) {
      var v=new BookingGuestVO();
      BeanUtils.copyProperties(g,v);
      list.add(v);
    }
    return new Claimed(snapshot,list);
  }
  @PostMapping("/internal/v1/order-workflows/complete") @Transactional public void complete(@RequestHeader(value="X-Internal-Token",defaultValue="")String token,@RequestBody Complete message) {
    guard.require(token);
    Claim c=message.claim();
    valid(c);
    var o=lock(c.orderId());
    var op=jdbc.queryForMap("SELECT * FROM order_workflow WHERE operation_id=? FOR UPDATE",c.operationId());
    if(((Number)op.get("order_id")).longValue()!=c.orderId()||!c.requestHash().equals(op.get("request_hash"))||!c.domain().equals(op.get("domain_name"))||!c.action().equals(op.get("action")))throw new BusinessException(409,"协调操作内容冲突");
    if("COMPLETED".equals(op.get("status")))return;
    if(!"ACTIVE".equals(op.get("status")))throw new BusinessException(409,"协调操作已撤销");
    String active=jdbc.queryForObject("SELECT active_workflow_id FROM booking_order WHERE order_id=?",String.class,c.orderId());
    if(!c.operationId().equals(active))throw new BusinessException(409,"协调所有权已变化");
    if(!Set.of("MOCK_PAY","PAYMENT_CAPTURE").contains(c.action()))state(c,o);
    var patch=message.patch();
    String expected=switch(c.action()) {
      case "MOCK_PAY","PAYMENT_CAPTURE"->"CONFIRMED";
      case "REFUND_APPLY","REFUND_REJECT"->"CONFIRMED";
      case "REFUND_APPROVE"->"REFUNDED";
      case "CHECK_IN","EXTEND"->"CHECKED_IN";
      case "CHECK_OUT"->"CHECKED_OUT";
      case "NO_SHOW"->"NO_SHOW";
      case "COMPLETE"->"COMPLETED";
      default->throw new IllegalStateException();
    }
    ;
    String target=patch.getOrDefault("order_status",o.getOrderStatus()).toString();
    if(!expected.equals(target))throw new BusinessException(409,"订单状态协调结果无效");
    String expectedPayment=switch(c.action()) {
      case "REFUND_APPLY"->"REFUNDING";
      case "REFUND_APPROVE"->"REFUNDED";
      default->"PAID";
    }
    ;
    if(!expectedPayment.equals(patch.getOrDefault("payment_status",o.getPaymentStatus())))throw new BusinessException(409,"付款状态协调结果无效");
    String requiredEffect=switch(c.action()) {
      case "MOCK_PAY","PAYMENT_CAPTURE"->"CONFIRM";
      case "REFUND_APPROVE"->"REFUND";
      case "EXTEND"->"EXTEND";
      default->"NONE";
    }
    ;
    long inventoryEffects=message.effects().stream().filter(e->Set.of("CONFIRM","REFUND","EXTEND").contains(e.kind())).count();
    if((requiredEffect.equals("NONE")&&inventoryEffects!=0)||(!requiredEffect.equals("NONE")&&(inventoryEffects!=1||message.effects().stream().noneMatch(e->requiredEffect.equals(e.kind())))))throw new BusinessException(409,"库存协调指令不完整或重复");
    if("EXTEND".equals(c.action()))validateExtension(o,patch,message.effects());
    for(var e:message.effects()) {
      switch(e.kind()) {
        case "CONFIRM"-> {
          if(!Set.of("MOCK_PAY","PAYMENT_CAPTURE").contains(c.action()))throw new BusinessException(409,"非法库存确认");
          inventory.confirm(o);
        }
        case "REFUND"-> {
          if(!"REFUND_APPROVE".equals(c.action()))throw new BusinessException(409,"非法退款释放");
          inventory.refund(o);
        }
        case "EXTEND"-> {
          if(!"EXTEND".equals(c.action()))throw new BusinessException(409,"非法续住库存操作");
          inventory.extend(o,LocalDate.parse(e.until()));
        }
        case "COUPON_USED"-> {
        }
        default->throw new BusinessException(400,"未知协调副作用");
      }
    }
    var permitted=new HashSet<>(List.of("order_status","payment_status"));
    if("EXTEND".equals(c.action()))permitted.addAll(List.of("check_out_date","nights","total_amount","original_amount"));
    if(!permitted.containsAll(patch.keySet()))throw new BusinessException(400,"协调字段超出领域权限");
    if(patch.containsKey("order_status"))o.setOrderStatus(target);
    if(patch.containsKey("payment_status"))o.setPaymentStatus(patch.get("payment_status").toString());
    if("EXTEND".equals(c.action())) {
      o.setCheckOutDate(LocalDate.parse(patch.get("check_out_date").toString()));
      o.setNights(Integer.valueOf(patch.get("nights").toString()));
      o.setTotalAmount(new java.math.BigDecimal(patch.get("total_amount").toString()));
      o.setOriginalAmount(new java.math.BigDecimal(patch.get("original_amount").toString()));
    }
    orders.updateById(o);
    jdbc.update("UPDATE booking_order SET active_workflow_id=NULL WHERE order_id=? AND active_workflow_id=?",c.orderId(),c.operationId());
    jdbc.update("UPDATE order_workflow SET status='COMPLETED',updated_at=CURRENT_TIMESTAMP(6) WHERE operation_id=?",c.operationId());
  }
  @PostMapping("/internal/v1/order-workflows/abort") @Transactional public void abort(@RequestHeader(value="X-Internal-Token",defaultValue="")String token,@RequestBody Abort message) {
    guard.require(token);
    if(message==null||message.operationId()==null||!message.operationId().matches("[0-9a-f-]{36}")||message.orderId()==null||message.domain()==null||!Set.of("payment","stay").contains(message.domain()))throw new BusinessException(403,"无效协调领域");
    lock(message.orderId());
    var rows=jdbc.queryForList("SELECT * FROM order_workflow WHERE operation_id=? FOR UPDATE",message.operationId());
    if(rows.isEmpty())jdbc.update("INSERT INTO order_workflow(operation_id,domain_name,action,order_id,request_hash,status) VALUES(?,?,'ABORT',?,'','ABORTED')",message.operationId(),message.domain(),message.orderId());
    else {
      var previous=rows.get(0);
      if(((Number)previous.get("order_id")).longValue()!=message.orderId()||!message.domain().equals(previous.get("domain_name")))throw new BusinessException(409,"协调所有者不匹配");
      if("COMPLETED".equals(previous.get("status")))return;
      jdbc.update("UPDATE order_workflow SET status='ABORTED',updated_at=CURRENT_TIMESTAMP(6) WHERE operation_id=?",message.operationId());
    }
    jdbc.update("UPDATE booking_order SET active_workflow_id=NULL WHERE order_id=? AND active_workflow_id=?",message.orderId(),message.operationId());
  }
  private void validateExtension(com.smarthotel.entity.BookingOrder order,Map<String,Object> patch,List<Effect> effects) {
    try {
      LocalDate until=LocalDate.parse(patch.get("check_out_date").toString());
      long extra=java.time.temporal.ChronoUnit.DAYS.between(order.getCheckOutDate(),until);
      int nights=Integer.parseInt(patch.get("nights").toString());
      var total=new java.math.BigDecimal(patch.get("total_amount").toString());
      var original=new java.math.BigDecimal(patch.get("original_amount").toString());
      var oldOriginal=order.getOriginalAmount()==null?order.getTotalAmount():order.getOriginalAmount();
      if(extra<1||extra>30||nights!=order.getNights()+extra||total.compareTo(order.getTotalAmount())<0||original.subtract(oldOriginal).compareTo(total.subtract(order.getTotalAmount()))!=0||effects.stream().filter(e->"EXTEND".equals(e.kind())).anyMatch(e->!until.toString().equals(e.until())))throw new IllegalArgumentException();
    }
    catch(RuntimeException invalid) {
      throw new BusinessException(409,"续住日期、晚数或金额协调结果无效");
    }
  }
}

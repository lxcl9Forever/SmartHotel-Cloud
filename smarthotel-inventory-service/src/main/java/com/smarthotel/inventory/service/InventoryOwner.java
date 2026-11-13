package com.smarthotel.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarthotel.contract.InventoryTccContract.*;
import com.smarthotel.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Owns only inventory state; never reads or locks the order database. */
@Service @RequiredArgsConstructor
public class InventoryOwner {
 private final JdbcTemplate jdbc;
 private final InventoryProjection projection;
 private final ObjectMapper json;
 private final RoomCapacity capacity;
 private void valid(Command c) {
  if(c==null||c.kind()==null||c.attemptId()==null||!c.attemptId().matches("[a-zA-Z0-9-]{1,36}")||c.orderId()==null||c.orderId()<=0||c.roomTypeId()==null||c.roomTypeId()<=0||c.count()==null||c.count()<=0||c.from()==null||c.to()==null||!c.from().isBefore(c.to())||ChronoUnit.DAYS.between(c.from(),c.to())>366||!Set.of("RESERVE","RESERVE_PAID","CONFIRM","RELEASE_LOCKED","RELEASE_SOLD","EXTEND").contains(c.kind()))throw new BusinessException(400,"无效的库存交接指令");
 }
 private String body(Command c){try{return json.writeValueAsString(c);}catch(Exception e){throw new IllegalStateException(e);}}
 private Map<String,Object> attempt(Command c,String initial) {
  String body=body(c);
  jdbc.update("INSERT INTO inventory_tcc_attempt(attempt_id,order_id,operation_key,payload_json,phase) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE attempt_id=attempt_id",c.attemptId(),c.orderId(),c.operationKey(),body,initial);
  var row=jdbc.queryForMap("SELECT * FROM inventory_tcc_attempt WHERE attempt_id=? FOR UPDATE",c.attemptId());
  if(!body.equals(row.get("payload_json")))throw new BusinessException(409,"同一库存交接编号的内容不一致");return row;
 }
 private Reply reply(Map<String,Object> row){return new Reply((String)row.get("phase"),((Number)row.get("no_op")).intValue()!=0);}
 private Map<String,Object> reservation(Command c){return jdbc.queryForMap("SELECT * FROM inventory_reservation WHERE order_id=? FOR UPDATE",c.orderId());}
 private void metadata(Command c,Map<String,Object> r) {
  if(((Number)r.get("room_type_id")).longValue()!=c.roomTypeId()||((Number)r.get("room_count")).intValue()!=c.count())throw new BusinessException(409,"库存预占归属不一致");
  LocalDate begin=LocalDate.parse(r.get("check_in_date").toString()),end=LocalDate.parse(r.get("check_out_date").toString());
  if(c.kind().equals("EXTEND")?!end.equals(c.from()):!begin.equals(c.from())||!end.equals(c.to()))throw new BusinessException(409,"库存预占日期不一致");
 }
 @Transactional public Reply prepare(Command c) {
  valid(c);var a=attempt(c,"NEW");if(!a.get("phase").equals("NEW"))return reply(a);
  jdbc.update("INSERT INTO inventory_reservation(order_id,room_type_id,check_in_date,check_out_date,room_count,state) VALUES(?,?,?,?,?,'NONE') ON DUPLICATE KEY UPDATE order_id=order_id",c.orderId(),c.roomTypeId(),c.from(),c.to(),c.count());
  var r=reservation(c);
  var old=jdbc.queryForList("SELECT request_signature FROM inventory_operation WHERE operation_key=?",c.operationKey());
  if(!old.isEmpty()) {
   if(!c.signature().equals(old.get(0).get("request_signature")))throw new BusinessException(409,"库存指令参数冲突");
   jdbc.update("UPDATE inventory_tcc_attempt SET phase='COMMITTED',no_op=1 WHERE attempt_id=?",c.attemptId());return new Reply("COMMITTED",true);
  }
  metadata(c,r);
  if(r.get("pending_attempt_id")!=null)throw new BusinessException(409,"上一笔库存交接正在恢复，请稍后重试");
  String required=switch(c.kind()){case "RESERVE","RESERVE_PAID"->"NONE";case "CONFIRM","RELEASE_LOCKED"->"RESERVED";default->"CONFIRMED";};
  if(!required.equals(r.get("state")))throw new BusinessException(409,"库存预占状态不允许此操作");
  switch(c.kind()) {
   case "RESERVE","RESERVE_PAID","EXTEND" -> change(c,1,0,true);
   case "CONFIRM" -> change(c,-1,1,false);
   case "RELEASE_LOCKED" -> change(c,-1,0,false);
   case "RELEASE_SOLD" -> change(c,0,-1,false);
  }
  jdbc.update("UPDATE inventory_reservation SET pending_attempt_id=? WHERE order_id=?",c.attemptId(),c.orderId());
  jdbc.update("UPDATE inventory_tcc_attempt SET phase='PREPARED' WHERE attempt_id=?",c.attemptId());return new Reply("PREPARED",false);
 }
 @Transactional public Reply decide(Decision d) {
  if(d==null||d.decision()==null)throw new BusinessException(400,"无效的库存决定");Command c=d.command();valid(c);if(!Set.of("COMMIT","ABORT").contains(d.decision()))throw new BusinessException(400,"无效的库存决定");
  // Abort-before-Try persists a tombstone, fencing a delayed prepare request.
  var a=attempt(c,d.decision().equals("ABORT")?"ABORTED":"NEW");
  String phase=(String)a.get("phase");if(Set.of("COMMITTED","ABORTED").contains(phase))return reply(a);
  if(!phase.equals("PREPARED"))throw new BusinessException(409,"库存指令尚未预留");
  var r=reservation(c);if(!c.attemptId().equals(r.get("pending_attempt_id")))throw new BusinessException(409,"库存交接占位不匹配");
  String next=(String)r.get("state");boolean commit=d.decision().equals("COMMIT");
  if(commit) {
   switch(c.kind()) {
    case "RESERVE" -> next="RESERVED";
    case "RESERVE_PAID","CONFIRM" -> {change(c,-1,1,true);next="CONFIRMED";}
    case "RELEASE_LOCKED" -> {change(c,-1,0,true);next="RELEASED";}
    case "RELEASE_SOLD" -> {change(c,0,-1,true);next="RELEASED";}
    case "EXTEND" -> {change(c,-1,1,true);jdbc.update("UPDATE inventory_reservation SET check_out_date=? WHERE order_id=?",c.to(),c.orderId());}
   }
   jdbc.update("INSERT INTO inventory_operation(operation_key,order_id,operation_type,request_signature,state,completed_at) VALUES(?,?,?,?,'DONE',CURRENT_TIMESTAMP)",c.operationKey(),c.orderId(),c.kind(),c.signature());
  } else if(Set.of("RESERVE","RESERVE_PAID","EXTEND").contains(c.kind()))change(c,-1,0,true);
  jdbc.update("UPDATE inventory_reservation SET state=?,pending_attempt_id=NULL WHERE order_id=?",next,c.orderId());
  jdbc.update("UPDATE inventory_tcc_attempt SET phase=? WHERE attempt_id=?",commit?"COMMITTED":"ABORTED",c.attemptId());
  return new Reply(commit?"COMMITTED":"ABORTED",false);
 }
 private void change(Command c,int locked,int sold,boolean apply) {
  var days=jdbc.queryForList("SELECT inventory_id,total_qty,locked_qty,sold_qty FROM room_inventory WHERE room_type_id=? AND inventory_date>=? AND inventory_date<? ORDER BY inventory_date FOR UPDATE",c.roomTypeId(),c.from(),c.to());
  if(days.size()!=ChronoUnit.DAYS.between(c.from(),c.to()))throw new BusinessException(409,"库存日期不完整");
  int dl=locked*c.count(),ds=sold*c.count();
  for(var day:days){long l=((Number)day.get("locked_qty")).longValue()+dl,s=((Number)day.get("sold_qty")).longValue()+ds;if(l<0||s<0||l+s>((Number)day.get("total_qty")).longValue())throw new BusinessException(409,"所选日期库存不足或占用数量异常");}
  if(apply){for(var day:days)jdbc.update("UPDATE room_inventory SET locked_qty=locked_qty+?,sold_qty=sold_qty+?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE inventory_id=?",dl,ds,day.get("inventory_id"));projection.enqueue(c.roomTypeId(),c.from(),c.to());}
 }
 @Transactional public void setTotal(SetTotal c) {
  if(c==null||c.roomTypeId()==null||c.date()==null||c.totalQty()==null||c.expectedVersion()==null)throw new BusinessException(400,"房量参数不完整");
  int totalRooms=capacity.totalRooms(c.roomTypeId());
  var row=jdbc.queryForMap("SELECT * FROM room_inventory WHERE room_type_id=? AND inventory_date=? FOR UPDATE",c.roomTypeId(),c.date());
  if(c.totalQty()==null||c.totalQty()<0||c.totalQty()>totalRooms||c.totalQty()<((Number)row.get("locked_qty")).intValue()+((Number)row.get("sold_qty")).intValue())throw new BusinessException(400,"房量超出实体容量或小于占用数量");
  if(c.expectedVersion()==null||c.expectedVersion()!=((Number)row.get("version")).intValue())throw new BusinessException(409,"库存已更新，请刷新后重试");
  jdbc.update("UPDATE room_inventory SET total_qty=?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE inventory_id=?",c.totalQty(),row.get("inventory_id"));projection.enqueue(c.roomTypeId(),c.date(),c.date().plusDays(1));
 }
}

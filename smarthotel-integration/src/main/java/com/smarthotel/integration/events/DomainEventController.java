package com.smarthotel.integration.events;
import com.smarthotel.integration.*;
import com.smarthotel.exception.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
@RestController public class DomainEventController {
  private final JdbcTemplate jdbc;
  private final InternalGuard guard;
  private final DomainOwnership owner;
  private final RowStore rows;
  private final ObjectMapper json;
  private final java.util.List<DomainEventListener> listeners;
  private final DomainEvents events;
  public DomainEventController(JdbcTemplate jdbc,InternalGuard guard,DomainOwnership owner,RowStore rows,ObjectMapper json,java.util.List<DomainEventListener> listeners,DomainEvents events) {
    this.jdbc=jdbc;
    this.guard=guard;
    this.owner=owner;
    this.rows=rows;
    this.json=json;
    this.listeners=listeners;
    this.events=events;
  }
  @PostMapping("/internal/v1/domain-events") @Transactional public void receive(@RequestHeader(value="X-Internal-Token",defaultValue="") String token,@RequestBody DomainEvent event) {
    guard.require(token);
    String table=owner.projections().get(event.source()+":"+event.entityType());
    if(table==null)return;
    if(event.version()<1||event.eventId()==null||event.aggregateId()==null)throw new BusinessException(400,"事件契约无效");
    try {
      String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(event)));
      int inserted=jdbc.update("INSERT IGNORE INTO domain_inbox(event_id,source,payload_hash) VALUES(?,?,?)",event.eventId(),event.source(),hash);
      if(inserted==0) {
        String original=jdbc.queryForObject("SELECT payload_hash FROM domain_inbox WHERE event_id=?",String.class,event.eventId());
        if(!hash.equals(original))throw new BusinessException(409,"事件标识与内容冲突");
        return;
      }
    }
    catch(BusinessException e) {
      throw e;
    }
    catch(Exception e) {
      throw new IllegalStateException(e);
    }
    String key=event.source()+":"+event.entityType();
    jdbc.update("INSERT IGNORE INTO projection_revision(entity_type,aggregate_id,version) VALUES(?,?,0)",key,event.aggregateId());
    long version=jdbc.queryForObject("SELECT version FROM projection_revision WHERE entity_type=? AND aggregate_id=? FOR UPDATE",Long.class,key,event.aggregateId());
    if(version>=event.version())return;
    var before=listeners.isEmpty()?java.util.Map.<String,java.util.Map<String,java.util.Map<String,Object>>>of():events.capture();
    var columns=rows.columns(table);
    if(!columns.contains(event.primaryKey())||!rows.primaryKey(table).equals(event.primaryKey()))throw new BusinessException(400,"投影主键无效");
    if(event.deleted())jdbc.update("DELETE FROM "+rows.id(table)+" WHERE "+rows.id(event.primaryKey())+"=?",event.aggregateId());
    else {
      if(!Objects.equals(String.valueOf(event.row().get(event.primaryKey())),event.aggregateId()))throw new BusinessException(400,"事件聚合标识不一致");
      rows.upsert(table,event.primaryKey(),event.row());
    }
    jdbc.update("UPDATE projection_revision SET version=? WHERE entity_type=? AND aggregate_id=?",event.version(),key,event.aggregateId());
    for(var listener:listeners)listener.accepted(event);
    if(!listeners.isEmpty())events.changed(before);
  }
  @GetMapping("/internal/v1/domain-events/health") public Map<String,Object> health(@RequestHeader(value="X-Internal-Token",defaultValue="") String token) {
    guard.require(token);
    var result=new LinkedHashMap<String,Object>();
    result.put("domain",owner.domain());
    result.put("pending",jdbc.queryForObject("SELECT COUNT(*) FROM domain_outbox WHERE status='PENDING'",Long.class));
    result.put("dead",jdbc.queryForObject("SELECT COUNT(*) FROM domain_outbox WHERE status='DEAD'",Long.class));
    result.put("workflowPending",jdbc.queryForObject("SELECT COUNT(*) FROM domain_workflow WHERE status IN ('INTENT','COMMITTED','ABORTING')",Long.class));
    if(owner.domain().equals("order")){
      result.put("authoritativeActive",jdbc.queryForObject("SELECT COUNT(*) FROM order_workflow WHERE status='ACTIVE'",Long.class));
      result.put("marketingPending",jdbc.queryForObject("SELECT COUNT(*) FROM marketing_tcc_journal WHERE done=0",Long.class));
    }
    return result;
  }
  @PostMapping("/internal/v1/domain-events/replay") public void replay(@RequestHeader(value="X-Internal-Token",defaultValue="") String token) {
    guard.require(token);
    jdbc.update("UPDATE domain_outbox SET status='PENDING',attempts=0,next_attempt=CURRENT_TIMESTAMP WHERE status='DEAD'");
  }
}

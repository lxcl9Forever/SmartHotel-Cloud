package com.smarthotel.integration.workflow;
import com.smarthotel.integration.*;
import com.smarthotel.integration.events.DomainEvents;
import com.smarthotel.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import java.util.*;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.smarthotel.integration.workflow.WorkflowContract.*;
@Component public class WorkflowCoordinator {
  private final JdbcTemplate jdbc;
  private final RowStore rows;
  private final ObjectMapper json;
  private final InternalHttp http;
  private final DomainOwnership owner;
  private final DomainEvents events;
  private final TransactionTemplate tx;
  private final String orderUrl;
  private final AtomicBoolean recovering=new AtomicBoolean();
  public WorkflowCoordinator(JdbcTemplate jdbc,RowStore rows,ObjectMapper json,InternalHttp http,DomainOwnership owner,DomainEvents events,PlatformTransactionManager manager,@Value("${ORDER_SERVICE_URL:http://127.0.0.1:8082}") String orderUrl) {
    this.jdbc=jdbc;
    this.rows=rows;
    this.json=json;
    this.http=http;
    this.owner=owner;
    this.events=events;
    this.tx=new TransactionTemplate(manager);
    this.orderUrl=orderUrl;
  }
  public Object execute(ProceedingJoinPoint call,WorkflowPolicy.Operation operation)throws Throwable {
    String hash=hash(events.encode(call.getArgs()));
    String key=operation.action()+":"+operation.orderId()+":"+operation.key();
    String candidate=UUID.randomUUID().toString();
    String id=tx.execute(s-> {
      jdbc.update("INSERT IGNORE INTO domain_workflow(operation_id,request_key,request_hash,action,order_id,status) VALUES(?,?,?,?,?,'INTENT')",candidate,key,hash,operation.action(),operation.orderId());return jdbc.queryForObject("SELECT operation_id FROM domain_workflow WHERE request_key=?",String.class,key);
    }
    );
    try {
      Object result=tx.execute(s-> {
        var row=jdbc.queryForMap("SELECT * FROM domain_workflow WHERE operation_id=? FOR UPDATE",id);if(!hash.equals(row.get("request_hash")))throw new BusinessException(409,"同一个幂等标识不能用于不同请求");String status=row.get("status").toString();
        if(Set.of("COMMITTED","DELIVERED").contains(status))return decode(row.get("result_json"),call);
        if(!"INTENT".equals(status))throw new BusinessException(409,"该业务操作正在恢复，请稍后查询");
        var claim=new Claim(id,owner.domain(),operation.action(),operation.orderId(),hash);var claimed=http.post(orderUrl+"/internal/v1/order-workflows/claim",claim,Claimed.class);if(claimed==null||claimed.order()==null)throw new BusinessException(503,"订单协调响应无效");
        var snapshot=json.convertValue(claimed.order(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {
        }
        );var snake=new LinkedHashMap<String,Object>();snapshot.forEach((k,v)->snake.put(k.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT),v));rows.upsert("order_snapshot","order_id",snake);
        if(!claimed.guests().isEmpty() && rows.columns("guest_snapshot").contains("guest_id")) {
          jdbc.update("DELETE FROM guest_snapshot WHERE order_id=?",operation.orderId());for(var guest:claimed.guests()) {
            var map=json.convertValue(guest,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {
            }
            );var g=new LinkedHashMap<String,Object>();map.forEach((k,v)->g.put(k.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT),v));g.put("order_id",operation.orderId());rows.upsert("guest_snapshot","guest_id",g);
          }
        }
        var before=events.capture();WorkflowContext.begin(id);try {
          Object value;try {
            value=call.proceed();
          }
          catch(Throwable failure) {
            throw new CallFailure(failure);
          }
          var changed=jdbc.query("SELECT * FROM order_snapshot WHERE order_id=?",(rs,n)->rows.row(rs),operation.orderId()).get(0);var patch=new LinkedHashMap<String,Object>();for(var field:List.of("order_status","payment_status","check_out_date","nights","total_amount","original_amount"))if(!Objects.equals(String.valueOf(snake.get(field)),String.valueOf(changed.get(field))))patch.put(field,changed.get(field));var complete=new Complete(claim,patch,WorkflowContext.effects());jdbc.update("UPDATE domain_workflow SET status='COMMITTED',completion_json=?,result_json=?,updated_at=CURRENT_TIMESTAMP(6) WHERE operation_id=?",events.encode(complete),events.encode(value),id);events.changed(before);return value;
        }
        finally {
          WorkflowContext.end();
        }
      }
      );
      if(!finish(id))throw new BusinessException(503,"操作已受理，状态同步正在恢复，请查询原订单，不要重复支付");
      return result;
    }
    catch(CallFailure failure) {
      abortIfUncommitted(id);
      throw failure.getCause();
    }
    catch(RuntimeException failure) {
      abortIfUncommitted(id);
      throw failure;
    }
  }
  private Object decode(Object raw,ProceedingJoinPoint call) {
    if(raw==null||"null".equals(raw.toString()))return null;
    try {
      return json.readValue(raw.toString(),json.getTypeFactory().constructType(((MethodSignature)call.getSignature()).getMethod().getGenericReturnType()));
    }
    catch(Exception e) {
      throw new IllegalStateException(e);
    }
  }
  private String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    catch(Exception e) {
      throw new IllegalStateException(e);
    }
  }
  private boolean finish(String id) {
    try {
      var row=jdbc.queryForMap("SELECT status,completion_json FROM domain_workflow WHERE operation_id=?",id);
      if("DELIVERED".equals(row.get("status")))return true;
      if(!"COMMITTED".equals(row.get("status")))return false;
      var message=json.readValue(row.get("completion_json").toString(),Complete.class);
      http.post(orderUrl+"/internal/v1/order-workflows/complete",message,Void.class);
      jdbc.update("UPDATE domain_workflow SET status='DELIVERED',updated_at=CURRENT_TIMESTAMP(6),last_error=NULL WHERE operation_id=? AND status='COMMITTED'",id);
      return true;
    }
    catch(Exception failure) {
      jdbc.update("UPDATE domain_workflow SET attempts=attempts+1,last_error=? WHERE operation_id=?",failure.getClass().getSimpleName(),id);
      return false;
    }
  }
  private void abortIfUncommitted(String id) {
    try {
      tx.executeWithoutResult(s-> {
        var records=jdbc.queryForList("SELECT * FROM domain_workflow WHERE operation_id=? FOR UPDATE NOWAIT",id);if(records.isEmpty())return;var row=records.get(0);if(!"INTENT".equals(row.get("status"))&&!"ABORTING".equals(row.get("status")))return;jdbc.update("UPDATE domain_workflow SET status='ABORTING',updated_at=CURRENT_TIMESTAMP(6) WHERE operation_id=?",id);
      }
      );
      deliverAbort(id);
    }
    catch(RuntimeException busy) {
      /* Recovery retries only after any local writer releases its row lock. */
    }
  }
  private void deliverAbort(String id) {
    var row=jdbc.queryForMap("SELECT * FROM domain_workflow WHERE operation_id=?",id);
    if(!"ABORTING".equals(row.get("status")))return;
    try {
      http.post(orderUrl+"/internal/v1/order-workflows/abort",new Abort(id,owner.domain(),((Number)row.get("order_id")).longValue()),Void.class);
      jdbc.update("UPDATE domain_workflow SET status='ABORTED',request_key=CONCAT(request_key,':aborted:',operation_id),updated_at=CURRENT_TIMESTAMP(6) WHERE operation_id=? AND status='ABORTING'",id);
    }
    catch(RuntimeException failure) {
      jdbc.update("UPDATE domain_workflow SET attempts=attempts+1,last_error=? WHERE operation_id=?",failure.getClass().getSimpleName(),id);
    }
  }
  @Scheduled(fixedDelay=3000) public void recover() {
    if(!recovering.compareAndSet(false,true))return;
    try {
      for(var row:jdbc.queryForList("SELECT operation_id,status FROM domain_workflow WHERE status IN ('COMMITTED','ABORTING') OR (status='INTENT' AND created_at<TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP)) ORDER BY created_at LIMIT 30")) {
        String id=row.get("operation_id").toString();
        if("COMMITTED".equals(row.get("status")))finish(id);
        else abortIfUncommitted(id);
      }
    }
    catch(RuntimeException ignored) {
    }
    finally {
      recovering.set(false);
    }
  }
  private static class CallFailure extends RuntimeException {
    CallFailure(Throwable cause) {
      super(cause);
    }
  }
}

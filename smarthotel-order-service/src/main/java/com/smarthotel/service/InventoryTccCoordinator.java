package com.smarthotel.service;
import com.smarthotel.client.InventoryClient;
import com.smarthotel.contract.InventoryTccContract.*;
import com.smarthotel.exception.BusinessException;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.time.LocalDate;
import java.util.*;

@Service @Slf4j @ConditionalOnProperty(name="smarthotel.inventory.mode",havingValue="remote")
public class InventoryTccCoordinator {
 private final InventoryClient client;private final InventoryJournalStore store;private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final String token;
 public InventoryTccCoordinator(InventoryClient client,InventoryJournalStore store,JdbcTemplate jdbc,PlatformTransactionManager manager,@Value("${smarthotel.internal.token}") String token){this.client=client;this.store=store;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.token=token;}
 public void prepare(Command c) {
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Order transaction required");
  store.prepare(c);
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
   @Override public void afterCompletion(int status){
    if(status==STATUS_COMMITTED)finish(c,"COMMIT");else if(status==STATUS_ROLLED_BACK)finish(c,"ABORT");
    // UNKNOWN is resolved under an order-row lock after recovery, never guessed as rollback.
   }
  });
  try {var reply=client.prepare(token,c);if(!Set.of("PREPARED","COMMITTED").contains(reply.phase()))throw new BusinessException(503,"库存交接已撤销，请重试");}
  catch(FeignException e){throw new BusinessException(e.status()==409?409:503,e.status()==409?"库存不足或上一笔库存交接正在恢复，请稍后重试":"库存服务暂不可用，预订尚未完成，请稍后重试");}
 }
 private void finish(Command c,String decision) {
  try {
   store.choose(c.attemptId(),decision);var reply=client.decide(token,new Decision(c,decision));
   if((decision.equals("COMMIT")&&reply.phase().equals("COMMITTED"))||(decision.equals("ABORT")&&(reply.phase().equals("ABORTED")||reply.replayed())))store.done(c.attemptId());
   else store.failed(c.attemptId(),"DECISION_CONFLICT");
  }catch(RuntimeException failure){try{store.failed(c.attemptId(),failure.getClass().getSimpleName());}catch(RuntimeException ignored){}log.warn("Inventory decision {} deferred ({})",c.attemptId(),failure.getClass().getSimpleName());}
 }
 private String recover(Command c) {
  return tx.execute(s->{
   // NOWAIT avoids reading the old image of an order whose writer has not finished.
   var rows=jdbc.queryForList("SELECT order_status,payment_status,check_out_date FROM booking_order WHERE order_id=? FOR UPDATE NOWAIT",c.orderId());
   if(rows.isEmpty())return "ABORT";
   var order=rows.get(0);String state=(String)order.get("order_status"),payment=(String)order.get("payment_status");
   boolean committed=switch(c.kind()) {
    case "RESERVE","RESERVE_PAID" -> true;
    case "CONFIRM" -> Set.of("PAID","REFUNDING","REFUNDED").contains(payment);
    case "RELEASE_LOCKED" -> Set.of("CANCELLED","CLOSED").contains(state);
    case "RELEASE_SOLD" -> state.equals("REFUNDED")||payment.equals("REFUNDED");
    case "EXTEND" -> !LocalDate.parse(order.get("check_out_date").toString()).isBefore(c.to());
    default -> throw new IllegalStateException("Unknown inventory command");
   };return committed?"COMMIT":"ABORT";
  });
 }
 @Scheduled(fixedDelay=5000) public void retryPending() {
  try {for(var row:store.pending()) {
   Command c=store.command(row);
   try{String decision=(String)row.get("decision");if(decision.equals("UNKNOWN"))decision=recover(c);finish(c,decision);}
   catch(RuntimeException locked){store.failed(c.attemptId(),locked.getClass().getSimpleName());}
  }}catch(RuntimeException unavailable){log.warn("Inventory journal recovery will retry ({})",unavailable.getClass().getSimpleName());}
 }
}

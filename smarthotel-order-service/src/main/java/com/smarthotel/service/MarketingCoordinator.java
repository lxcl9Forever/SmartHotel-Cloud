package com.smarthotel.service;
import static com.smarthotel.contract.MarketingContract.*;
import com.smarthotel.integration.*;
import com.smarthotel.integration.events.DomainEvents;
import com.smarthotel.dto.booking.CreateBookingRequest;
import com.smarthotel.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;
@Service public class MarketingCoordinator {
  private final JdbcTemplate jdbc;
  private final JdbcTemplate journal;
  private final InternalHttp http;
  private final DomainEvents events;
  private final TransactionTemplate isolated;
  private final TransactionTemplate tx;
  private final String url;
  public MarketingCoordinator(JdbcTemplate jdbc,InternalHttp http,DomainEvents events,PlatformTransactionManager manager,MarketingJournalPool pool,@Value("${MARKETING_SERVICE_URL:http://127.0.0.1:8086}")String url) {
    this.jdbc=jdbc;
    this.http=http;
    this.events=events;
    this.url=url;
    this.journal=pool.jdbc;
    this.isolated=pool.tx;
    this.tx=new TransactionTemplate(manager);
  }
  public Quote prepare(Long customerId,CreateBookingRequest request,BigDecimal amount) {
    if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Booking transaction required");
    String id=UUID.randomUUID().toString();
    var command=new Prepare(id,customerId,request,amount);
    isolated.executeWithoutResult(s->journal.update("INSERT INTO marketing_tcc_journal(operation_id,decision,payload) VALUES(?,'UNKNOWN',?)",id,events.encode(command)));
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      public void afterCompletion(int status) {
        if(status==STATUS_COMMITTED)finish(id,"COMMIT");else if(status==STATUS_ROLLED_BACK)finish(id,"ABORT");
      }
    }
    );
    return http.post(url+"/internal/v1/marketing/reservations/prepare",command,Quote.class);
  }
  public void attach(String id,Long orderId) {
    isolated.executeWithoutResult(s->journal.update("UPDATE marketing_tcc_journal SET order_id=? WHERE operation_id=?",orderId,id));
  }
  private void finish(String id,String decision) {
    try {
      isolated.executeWithoutResult(s->journal.update("UPDATE marketing_tcc_journal SET decision=? WHERE operation_id=? AND decision='UNKNOWN'",decision,id));
      var row=journal.queryForMap("SELECT * FROM marketing_tcc_journal WHERE operation_id=?",id);
      String chosen=row.get("decision").toString();
      Long order=row.get("order_id")==null?null:((Number)row.get("order_id")).longValue();
      var reply=http.post(url+"/internal/v1/marketing/reservations/decide",new Decision(id,chosen,order),Phase.class);
      if(reply==null||!(chosen.equals("COMMIT")?"COMMITTED":"ABORTED").equals(reply.phase()))throw new BusinessException(409,"营销决议冲突");
      isolated.executeWithoutResult(s->journal.update("UPDATE marketing_tcc_journal SET done=1,last_error=NULL WHERE operation_id=?",id));
    }
    catch(RuntimeException failure) {
      isolated.executeWithoutResult(s->journal.update("UPDATE marketing_tcc_journal SET attempts=attempts+1,last_error=? WHERE operation_id=?",failure.getClass().getSimpleName(),id));
    }
  }
  @Scheduled(fixedDelay=5000) public void recover() {
    try {
      for(var row:journal.queryForList("SELECT * FROM marketing_tcc_journal WHERE done=0 AND created_at<TIMESTAMPADD(SECOND,-30,CURRENT_TIMESTAMP) ORDER BY created_at LIMIT 50")) {
        String id=row.get("operation_id").toString();
        try {
          String decision=row.get("decision").toString();
          if("UNKNOWN".equals(decision))decision=tx.execute(s-> {
            var orders=jdbc.queryForList("SELECT order_id FROM booking_order WHERE marketing_operation_id=? FOR UPDATE NOWAIT",id);if(orders.isEmpty())return "ABORT";Long order=((Number)orders.get(0).get("order_id")).longValue();isolated.executeWithoutResult(t->journal.update("UPDATE marketing_tcc_journal SET order_id=? WHERE operation_id=?",order,id));return "COMMIT";
          }
          );
          finish(id,decision);
        }
        catch(RuntimeException busy) {
          /* Never infer rollback from a locked or unavailable source transaction. */
        }
      }
    }
    catch(RuntimeException ignored) {
    }
  }
}

package com.smarthotel.integration.events;
import com.smarthotel.integration.InternalHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@Component public class OutboxDispatcher {
  private final com.smarthotel.integration.DomainOwnership owner;
  private final JdbcTemplate jdbc;
  private final InternalHttp http;
  private final ObjectMapper json;
  private final AtomicBoolean running=new AtomicBoolean();
  private static final Logger log=LoggerFactory.getLogger(OutboxDispatcher.class);
  public OutboxDispatcher(JdbcTemplate jdbc,InternalHttp http,ObjectMapper json,com.smarthotel.integration.DomainOwnership owner) {
    this.owner=owner;
    this.jdbc=jdbc;
    this.http=http;
    this.json=json;
  }
  @Scheduled(fixedDelayString="${smarthotel.events.retry-delay-ms:2000}") public void deliver() {
    if(!running.compareAndSet(false,true))return;
    try {
      for(var row:jdbc.queryForList("SELECT event_id,destination,url,payload,attempts FROM domain_outbox WHERE status='PENDING' AND next_attempt<=CURRENT_TIMESTAMP ORDER BY created_at,event_id LIMIT 100")) {
        try {
          var event=json.readValue(row.get("payload").toString(),DomainEvent.class);
          String target=owner.targets().get(row.get("destination").toString());
          if(target==null)throw new IllegalStateException("Unknown configured event destination");
          http.post(target+"/internal/v1/domain-events",event,Void.class);
          jdbc.update("UPDATE domain_outbox SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP,last_error=NULL WHERE event_id=? AND destination=?",row.get("event_id"),row.get("destination"));
        }
        catch(Exception failure) {
          int attempts=((Number)row.get("attempts")).intValue()+1;
          int delay=Math.min(60,1<<Math.min(6,attempts));
          jdbc.update("UPDATE domain_outbox SET attempts=attempts+1,last_error=?,next_attempt=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),status=? WHERE event_id=? AND destination=?",failure.getClass().getSimpleName(),delay,attempts>=20?"DEAD":"PENDING",row.get("event_id"),row.get("destination"));
        }
      }
    }
    catch(RuntimeException unavailable) {
      log.warn("Outbox scan deferred: {}",unavailable.getClass().getSimpleName());
    }
    finally {
      running.set(false);
    }
  }
}

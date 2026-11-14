package com.smarthotel.integration.events;
import com.smarthotel.integration.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
@Component public class DomainEvents {
  private final JdbcTemplate jdbc;
  private final RowStore rows;
  private final ObjectMapper json;
  private final ObjectProvider<DomainOwnership> owner;
  public DomainEvents(JdbcTemplate jdbc,RowStore rows,ObjectMapper json,ObjectProvider<DomainOwnership> owner) {
    this.jdbc=jdbc;
    this.rows=rows;
    this.json=json;
    this.owner=owner;
  }
  public Map<String,Map<String,Map<String,Object>>> capture() {
    var result=new LinkedHashMap<String,Map<String,Map<String,Object>>>();
    var boundary=owner.getIfAvailable();
    if(boundary==null)return result;
    boundary.ownedTables().forEach((table,pk)-> {
      var index=new LinkedHashMap<String,Map<String,Object>>();for(var row:rows.rows(table))index.put(String.valueOf(row.get(pk)),row);result.put(table,index);
    }
    );
    return result;
  }
  public void changed(Map<String,Map<String,Map<String,Object>>> before) {
    var boundary=owner.getIfAvailable();
    if(boundary==null)return;
    if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Outbox and owner write require one local transaction");
    var after=capture();
    for(var table:boundary.ownedTables().keySet()) {
      var old=before.getOrDefault(table,Map.of());
      var now=after.getOrDefault(table,Map.of());
      var ids=new HashSet<>(old.keySet());
      ids.addAll(now.keySet());
      for(var key:ids) {
        if(Objects.equals(encode(old.get(key)),encode(now.get(key)) ))continue;
        String pk=boundary.ownedTables().get(table);
        jdbc.update("INSERT INTO event_revision(entity_type,aggregate_id,version) VALUES(?,?,1) ON DUPLICATE KEY UPDATE version=version+1",table,key);
        long version=jdbc.queryForObject("SELECT version FROM event_revision WHERE entity_type=? AND aggregate_id=?",Long.class,table,key);
        var event=new DomainEvent(UUID.randomUUID().toString(),boundary.domain(),table,key,pk,version,!now.containsKey(key),now.getOrDefault(key,Map.of()));
        for(var target:boundary.targets().entrySet())if(boundary.audience(table).contains(target.getKey()))jdbc.update("INSERT INTO domain_outbox(event_id,destination,url,payload,status,attempts,next_attempt) VALUES(?,?,?,?,'PENDING',0,CURRENT_TIMESTAMP)",event.eventId(),target.getKey(),target.getValue()+"/internal/v1/domain-events",encode(event));
      }
    }
  }
  public void emitCurrent(String table,String key) {
    var boundary=owner.getIfAvailable();
    if(boundary==null||!boundary.ownedTables().containsKey(table))throw new IllegalArgumentException("Not a local aggregate");
    if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Owner transaction required");
    String pk=boundary.ownedTables().get(table);
    var found=rows.rows(table).stream().filter(row->String.valueOf(row.get(pk)).equals(key)).findFirst();
    if(found.isEmpty())return;
    jdbc.update("INSERT INTO event_revision(entity_type,aggregate_id,version) VALUES(?,?,1) ON DUPLICATE KEY UPDATE version=version+1",table,key);
    long version=jdbc.queryForObject("SELECT version FROM event_revision WHERE entity_type=? AND aggregate_id=?",Long.class,table,key);
    var event=new DomainEvent(UUID.randomUUID().toString(),boundary.domain(),table,key,pk,version,false,found.get());
    for(var target:boundary.targets().entrySet())if(boundary.audience(table).contains(target.getKey()))jdbc.update("INSERT INTO domain_outbox(event_id,destination,url,payload,status,attempts,next_attempt) VALUES(?,?,?,?,'PENDING',0,CURRENT_TIMESTAMP)",event.eventId(),target.getKey(),target.getValue()+"/internal/v1/domain-events",encode(event));
  }
  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    }
    catch(Exception e) {
      throw new IllegalStateException("Could not encode domain contract",e);
    }
  }
}

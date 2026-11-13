package com.smarthotel.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarthotel.contract.InventoryTccContract.Command;
import com.zaxxer.hikari.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;

/** A separate, small pool prevents REQUIRES_NEW journaling from exhausting the order pool. */
@Service @ConditionalOnProperty(name="smarthotel.inventory.mode",havingValue="remote")
public class InventoryJournalStore {
 private final HikariDataSource pool;private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;
 public InventoryJournalStore(@Value("${spring.datasource.url}") String url,@Value("${spring.datasource.username}") String user,@Value("${spring.datasource.password}") String password,ObjectMapper json) {
  this.json=json;var c=new HikariConfig();c.setJdbcUrl(url);c.setUsername(user);c.setPassword(password);c.setMaximumPoolSize(4);c.setMinimumIdle(1);c.setPoolName("inventory-decision-journal");c.setConnectionTimeout(3000);c.setInitializationFailTimeout(-1);
  if(url.startsWith("jdbc:mysql:"))c.setConnectionInitSql("SET time_zone='+08:00'");pool=new HikariDataSource(c);jdbc=new JdbcTemplate(pool);tx=new TransactionTemplate(new DataSourceTransactionManager(pool));
 }
 @PreDestroy public void close(){pool.close();}
 public void prepare(Command command) {
  try{String payload=json.writeValueAsString(command);tx.executeWithoutResult(s->jdbc.update("INSERT INTO inventory_tcc_journal(attempt_id,order_id,payload_json) VALUES(?,?,?)",command.attemptId(),command.orderId(),payload));}catch(Exception e){throw new IllegalStateException("Unable to persist inventory decision journal",e);}
 }
 public void choose(String id,String decision) {
  tx.executeWithoutResult(s->{
   jdbc.update("UPDATE inventory_tcc_journal SET decision=? WHERE attempt_id=? AND decision='UNKNOWN'",decision,id);
   String stored=jdbc.queryForObject("SELECT decision FROM inventory_tcc_journal WHERE attempt_id=?",String.class,id);
   if(!decision.equals(stored))throw new IllegalStateException("Inventory decision conflict");
  });
 }
 public void done(String id){tx.executeWithoutResult(s->jdbc.update("UPDATE inventory_tcc_journal SET state='DONE',last_error=NULL WHERE attempt_id=?",id));}
 public void failed(String id,String error){tx.executeWithoutResult(s->jdbc.update("UPDATE inventory_tcc_journal SET attempts=attempts+1,last_error=?,next_attempt_at=? WHERE attempt_id=? AND state='PENDING'",error,LocalDateTime.now().plusSeconds(5),id));}
 public List<Map<String,Object>> pending(){return jdbc.queryForList("SELECT * FROM inventory_tcc_journal WHERE state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 100");}
 public Command command(Map<String,Object> row){try{return json.readValue((String)row.get("payload_json"),Command.class);}catch(Exception e){throw new IllegalStateException("Unreadable inventory journal",e);}}
}

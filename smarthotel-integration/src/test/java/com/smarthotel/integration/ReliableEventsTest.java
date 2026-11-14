package com.smarthotel.integration;
import com.smarthotel.integration.events.*;
import com.smarthotel.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ReliableEventsTest {
  JdbcTemplate jdbc;
  TransactionTemplate tx;
  DomainEvents publisher;
  DomainEventController receiver;
  @BeforeEach void database() {
    var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
    jdbc=new JdbcTemplate(ds);
    tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    jdbc.execute("CREATE TABLE owner_order(id BIGINT PRIMARY KEY,name VARCHAR(100))");
    jdbc.execute("CREATE TABLE order_projection(id BIGINT PRIMARY KEY,name VARCHAR(100))");
    jdbc.execute("CREATE TABLE event_revision(entity_type VARCHAR(64),aggregate_id VARCHAR(100),version BIGINT,PRIMARY KEY(entity_type,aggregate_id))");
    jdbc.execute("CREATE TABLE domain_outbox(event_id VARCHAR(36),destination VARCHAR(32),url VARCHAR(500),payload CLOB,status VARCHAR(16),attempts INT,next_attempt TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,delivered_at TIMESTAMP,last_error VARCHAR(100),PRIMARY KEY(event_id,destination))");
    jdbc.execute("CREATE TABLE domain_inbox(event_id VARCHAR(36) PRIMARY KEY,source VARCHAR(32),payload_hash VARCHAR(64),received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    jdbc.execute("CREATE TABLE projection_revision(entity_type VARCHAR(100),aggregate_id VARCHAR(100),version BIGINT,PRIMARY KEY(entity_type,aggregate_id))");
    DomainOwnership boundary=new DomainOwnership() {
      public String domain() {
        return "source";
      }
      public Map<String,String> ownedTables() {
        return Map.of("owner_order","id");
      }
      public Map<String,String> projections() {
        return Map.of("source:owner_order","order_projection");
      }
      public Map<String,String> targets() {
        return Map.of("target","http://127.0.0.1:1");
      }
    }
    ;
    var beans=new DefaultListableBeanFactory();
    beans.registerSingleton("owner",boundary);
    var json=new ObjectMapper();
    var rows=new RowStore(jdbc);
    publisher=new DomainEvents(jdbc,rows,json,beans.getBeanProvider(DomainOwnership.class));
    receiver=new DomainEventController(jdbc,new InternalGuard("a-test-internal-token-over-thirty-two-characters"),boundary,rows,json,List.of(),publisher);
  }
  void receive(DomainEvent e) {
    tx.executeWithoutResult(s->receiver.receive("a-test-internal-token-over-thirty-two-characters",e));
  }
  DomainEvent event(String id,long version,String value,boolean deleted) {
    return new DomainEvent(id,"source","owner_order","1","id",version,deleted,deleted?Map.of():Map.of("id",1,"name",value));
  }
  @Test void ownerRollbackAlsoRollsBackOutbox() {
    assertThatThrownBy(()->tx.executeWithoutResult(s-> {
      var before=publisher.capture();jdbc.update("INSERT INTO owner_order VALUES(1,'new')");publisher.changed(before);throw new IllegalStateException("simulate rollback");
    }
    )).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM owner_order",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM domain_outbox",Integer.class)).isZero();
  }
  @Test void ownerCommitPersistsIntentInSameTransaction() {
    tx.executeWithoutResult(s-> {
      var before=publisher.capture();jdbc.update("INSERT INTO owner_order VALUES(1,'new')");publisher.changed(before);
    }
    );
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM domain_outbox WHERE status='PENDING'",Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT version FROM event_revision",Long.class)).isEqualTo(1);
  }
  @Test void repeatedAndOlderEventsDoNotOverwriteNewerProjection() {
    var newest=event("new",3,"three",false);
    receive(newest);
    receive(newest);
    receive(event("old",2,"two",false));
    assertThat(jdbc.queryForObject("SELECT name FROM order_projection WHERE id=1",String.class)).isEqualTo("three");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM domain_inbox",Integer.class)).isEqualTo(2);
  }
  @Test void eventIdCannotBeReusedForDifferentPayload() {
    receive(event("same",1,"one",false));
    assertThatThrownBy(()->receive(event("same",2,"two",false))).isInstanceOf(BusinessException.class);
    assertThat(jdbc.queryForObject("SELECT name FROM order_projection",String.class)).isEqualTo("one");
  }
  @Test void deletionTombstonePreventsStaleResurrection() {
    receive(event("create",1,"one",false));
    receive(event("delete",3,"",true));
    receive(event("late",2,"two",false));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_projection",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT version FROM projection_revision",Long.class)).isEqualTo(3);
  }
  @Test void callerCannotChooseAnAlternateColumnAsPrimaryKey() {
    var bad=new DomainEvent("bad","source","owner_order","three","name",3,false,Map.of("id",1,"name","three"));
    assertThatThrownBy(()->receive(bad)).isInstanceOf(BusinessException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM domain_inbox",Integer.class)).isZero();
  }
  @Test void internalEndpointRejectsMissingCredential() {
    assertThatThrownBy(()->tx.executeWithoutResult(s->receiver.receive("",event("none",1,"one",false)))).isInstanceOf(BusinessException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM domain_inbox",Integer.class)).isZero();
  }
}

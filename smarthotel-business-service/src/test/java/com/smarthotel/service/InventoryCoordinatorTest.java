package com.smarthotel.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarthotel.client.InventoryClient;
import com.smarthotel.contract.InventoryTccContract.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InventoryCoordinatorTest {
 JdbcTemplate db;TransactionTemplate tx;InventoryJournalStore store;InventoryClient client;InventoryTccCoordinator coordinator;
 Map<String,String> phases=new ConcurrentHashMap<>();AtomicBoolean offline=new AtomicBoolean();
 LocalDate day=LocalDate.of(2030,1,1);
 @BeforeEach void setup()throws Exception {
  String url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=50";
  var ds=new DriverManagerDataSource(url,"sa","");var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);db=new JdbcTemplate(ds);
  db.execute("CREATE TABLE booking_order(order_id BIGINT PRIMARY KEY,order_status VARCHAR(32),payment_status VARCHAR(32),check_out_date DATE)");
  var ddl=Files.readString(Path.of("../database/18_inventory_tcc.sql")).replace("USE smart_hotel_cloud;","");for(var sql:ddl.split(";"))if(!sql.isBlank())db.execute(sql);
  store=new InventoryJournalStore(url,"sa","",new ObjectMapper().registerModule(new JavaTimeModule()));client=mock(InventoryClient.class);
  when(client.prepare(anyString(),any())).thenAnswer(i->{Command c=i.getArgument(1);phases.putIfAbsent(c.attemptId(),"PREPARED");return new Reply(phases.get(c.attemptId()),false);});
  when(client.decide(anyString(),any())).thenAnswer(i->{if(offline.get())throw new IllegalStateException("injected lost network");Decision d=i.getArgument(1);String phase=d.decision().equals("COMMIT")?"COMMITTED":"ABORTED";phases.put(d.command().attemptId(),phase);return new Reply(phase,false);});
  coordinator=new InventoryTccCoordinator(client,store,db,manager,"synthetic-internal-token-32-characters");
 }
 @AfterEach void close(){store.close();}
 Command c(long id,String kind){return new Command(UUID.randomUUID().toString(),id,kind,1L,day,day.plusDays(2),1);}
 void order(long id,String state,String payment){db.update("INSERT INTO booking_order VALUES(?,?,?,?)",id,state,payment,day.plusDays(2));}
 void due(){db.update("UPDATE inventory_tcc_journal SET next_attempt_at=CURRENT_TIMESTAMP");}
 String decision(Command c){return db.queryForObject("SELECT decision FROM inventory_tcc_journal WHERE attempt_id=?",String.class,c.attemptId());}
 @Test void sourceCommitSendsFinalDecision(){var c=c(1,"RESERVE");tx.executeWithoutResult(s->{order(1,"WAIT_PAY","UNPAID");coordinator.prepare(c);assertThat(phases.get(c.attemptId())).isEqualTo("PREPARED");});assertThat(phases.get(c.attemptId())).isEqualTo("COMMITTED");assertThat(store.pending()).isEmpty();}
 @Test void journalSurvivesOrderRollbackAndCancelsRemoteTry(){var c=c(2,"RESERVE");assertThatThrownBy(()->tx.executeWithoutResult(s->{order(2,"WAIT_PAY","UNPAID");coordinator.prepare(c);throw new IllegalStateException("post-prepare SQL failure");})).isInstanceOf(IllegalStateException.class);assertThat(db.queryForObject("SELECT COUNT(*) FROM booking_order",Integer.class)).isZero();assertThat(decision(c)).isEqualTo("ABORT");assertThat(phases.get(c.attemptId())).isEqualTo("ABORTED");}
 @Test void lostFinalAckKeepsDurableCommitAndRetries(){var c=c(3,"RESERVE");offline.set(true);tx.executeWithoutResult(s->{order(3,"WAIT_PAY","UNPAID");coordinator.prepare(c);});assertThat(decision(c)).isEqualTo("COMMIT");assertThat(phases.get(c.attemptId())).isEqualTo("PREPARED");offline.set(false);due();coordinator.retryPending();assertThat(phases.get(c.attemptId())).isEqualTo("COMMITTED");assertThat(store.pending()).isEmpty();}
 @Test void crashBeforeTryLeavesAbortTombstone(){var c=c(4,"RESERVE");store.prepare(c);coordinator.retryPending();assertThat(decision(c)).isEqualTo("ABORT");assertThat(phases.get(c.attemptId())).isEqualTo("ABORTED");assertThat(client.prepare("token",c).phase()).isEqualTo("ABORTED");}
 @Test void restartRecoversCommitFromSourceRow(){var c=c(5,"RESERVE");order(5,"WAIT_PAY","UNPAID");store.prepare(c);phases.put(c.attemptId(),"PREPARED");coordinator.retryPending();assertThat(decision(c)).isEqualTo("COMMIT");}
 @Test void restartDoesNotGuessUncommittedUpdate()throws Exception {order(6,"WAIT_PAY","UNPAID");var c=c(6,"CONFIRM");store.prepare(c);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();try{var task=pool.submit(()->tx.executeWithoutResult(s->{db.update("UPDATE booking_order SET payment_status='PAID' WHERE order_id=6");entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}}));assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();coordinator.retryPending();assertThat(decision(c)).isEqualTo("UNKNOWN");verify(client,never()).decide(anyString(),any());release.countDown();task.get(3,TimeUnit.SECONDS);due();coordinator.retryPending();assertThat(decision(c)).isEqualTo("COMMIT");}finally{release.countDown();pool.shutdownNow();}}
 @Test void cancelledAndRefundedAndExtendedStatesResolveAfterCrash(){order(7,"CANCELLED","UNPAID");order(8,"REFUNDED","REFUNDED");order(9,"CHECKED_IN","PAID");var a=c(7,"RELEASE_LOCKED");var b=c(8,"RELEASE_SOLD");var e=c(9,"EXTEND");store.prepare(a);store.prepare(b);store.prepare(e);coordinator.retryPending();assertThat(decision(a)).isEqualTo("COMMIT");assertThat(decision(b)).isEqualTo("COMMIT");assertThat(decision(e)).isEqualTo("COMMIT");}
 @Test void oldImageAfterRolledBackPaymentAborts(){order(10,"WAIT_PAY","UNPAID");var c=c(10,"CONFIRM");store.prepare(c);coordinator.retryPending();assertThat(decision(c)).isEqualTo("ABORT");}
}

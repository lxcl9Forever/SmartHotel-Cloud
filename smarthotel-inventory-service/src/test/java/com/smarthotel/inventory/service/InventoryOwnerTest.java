package com.smarthotel.inventory.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarthotel.contract.InventoryTccContract.*;
import com.smarthotel.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryOwnerTest {
 JdbcTemplate db;TransactionTemplate tx;InventoryOwner owner;RoomCapacity capacity;LocalDate day=LocalDate.of(2030,1,1);
 @BeforeEach void setup()throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
  db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  capacity=mock(RoomCapacity.class);when(capacity.totalRooms(1L)).thenReturn(2);
  db.execute("CREATE TABLE room_inventory(inventory_id BIGINT AUTO_INCREMENT PRIMARY KEY,room_type_id BIGINT,inventory_date DATE,total_qty INT,locked_qty INT,sold_qty INT,version INT DEFAULT 0,update_time DATETIME,UNIQUE(room_type_id,inventory_date))");
  db.execute("CREATE TABLE inventory_operation(operation_key VARCHAR(96) PRIMARY KEY,order_id BIGINT,operation_type VARCHAR(32),request_signature VARCHAR(240),state VARCHAR(16),completed_at DATETIME)");
  String ddl=Files.readString(Path.of("../database/18_inventory_tcc.sql")).replace("USE smart_hotel_cloud;","");
  for(String sql:ddl.split(";"))if(!sql.isBlank())db.execute(sql);
  for(int i=0;i<4;i++)db.update("INSERT INTO room_inventory(room_type_id,inventory_date,total_qty,locked_qty,sold_qty) VALUES(1,?,1,0,0)",day.plusDays(i));
  owner=new InventoryOwner(db,mock(InventoryProjection.class),new ObjectMapper().registerModule(new JavaTimeModule()),capacity);
 }
 Command c(long id,String kind){return new Command(UUID.randomUUID().toString(),id,kind,1L,day,day.plusDays(2),1);}
 Reply prepare(Command c){return tx.execute(s->owner.prepare(c));} Reply decide(Command c,String d){return tx.execute(s->owner.decide(new Decision(c,d)));}
 int qty(String col){return db.queryForObject("SELECT SUM("+col+") FROM room_inventory",Integer.class);}
 void reserve(long id){var c=c(id,"RESERVE");prepare(c);decide(c,"COMMIT");}
 @Test void reserveConfirmRefundAndDuplicateReceipts(){
  var r=c(1,"RESERVE");prepare(r);assertThat(qty("locked_qty")).isEqualTo(2);decide(r,"COMMIT");decide(r,"COMMIT");
  assertThat(prepare(c(1,"RESERVE")).replayed()).isTrue();assertThat(qty("locked_qty")).isEqualTo(2);
  var p=c(1,"CONFIRM");prepare(p);assertThat(qty("sold_qty")).isZero();decide(p,"COMMIT");
  var f=c(1,"RELEASE_SOLD");prepare(f);assertThat(qty("sold_qty")).isEqualTo(2);decide(f,"ABORT");assertThat(qty("sold_qty")).isEqualTo(2);
  var retry=c(1,"RELEASE_SOLD");prepare(retry);decide(retry,"COMMIT");assertThat(qty("locked_qty+sold_qty")).isZero();
 }
 @Test void cancellationDoesNotFreeStockUntilSourceCommit(){
  reserve(2);var cancel=c(2,"RELEASE_LOCKED");prepare(cancel);assertThat(qty("locked_qty")).isEqualTo(2);
  assertThatThrownBy(()->prepare(c(3,"RESERVE"))).isInstanceOf(BusinessException.class);
  decide(cancel,"COMMIT");prepare(c(3,"RESERVE"));assertThat(qty("locked_qty")).isEqualTo(2);
 }
 @Test void abortBeforeTryFencesDelayedNetworkRequest(){var r=c(4,"RESERVE");assertThat(decide(r,"ABORT").phase()).isEqualTo("ABORTED");assertThat(prepare(r).phase()).isEqualTo("ABORTED");assertThat(qty("locked_qty")).isZero();}
 @Test void failedSourceTransactionReleasesPreparedStockExactlyOnce(){var r=c(5,"RESERVE");prepare(r);decide(r,"ABORT");decide(r,"ABORT");assertThat(qty("locked_qty")).isZero();reserve(5);assertThat(qty("locked_qty")).isEqualTo(2);}
 @Test void failedOneNightRollsBackWholePrepare(){db.update("UPDATE room_inventory SET total_qty=0 WHERE inventory_date=?",day.plusDays(1));assertThatThrownBy(()->prepare(c(6,"RESERVE"))).isInstanceOf(BusinessException.class);assertThat(qty("locked_qty")).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM inventory_tcc_attempt",Integer.class)).isZero();}
 @Test void competingLastRoomHasOneWinner()throws Exception {var pool=Executors.newFixedThreadPool(6);var gate=new CountDownLatch(1);var wins=new AtomicInteger();try{var tasks=new ArrayList<Future<?>>();for(int i=10;i<16;i++){long id=i;tasks.add(pool.submit(()->{try{gate.await();prepare(c(id,"RESERVE"));wins.incrementAndGet();}catch(BusinessException expected){}catch(InterruptedException e){throw new RuntimeException(e);}}));}gate.countDown();for(var t:tasks)t.get(15,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertThat(wins.get()).isEqualTo(1);assertThat(qty("locked_qty")).isEqualTo(2);}
 @Test void extensionHoldsThenCommitsExtraNightsAndReplays(){var paid=c(20,"RESERVE_PAID");prepare(paid);decide(paid,"COMMIT");assertThat(qty("sold_qty")).isEqualTo(2);var e=new Command(UUID.randomUUID().toString(),20L,"EXTEND",1L,day.plusDays(2),day.plusDays(4),1);prepare(e);assertThat(qty("locked_qty")).isEqualTo(2);decide(e,"COMMIT");assertThat(qty("sold_qty")).isEqualTo(4);assertThat(qty("locked_qty")).isZero();var duplicate=new Command(UUID.randomUUID().toString(),20L,"EXTEND",1L,e.from(),e.to(),1);assertThat(prepare(duplicate).replayed()).isTrue();}
 @Test void cannotUndoReceiptFromEarlierSuccessfulAttempt(){reserve(30);var duplicate=c(30,"RESERVE");assertThat(prepare(duplicate).replayed()).isTrue();assertThat(decide(duplicate,"ABORT").replayed()).isTrue();assertThat(qty("locked_qty")).isEqualTo(2);}
 @Test void changingAttemptPayloadIsRejected(){var r=c(40,"RESERVE");prepare(r);var changed=new Command(r.attemptId(),41L,r.kind(),1L,r.from(),r.to(),1);assertThatThrownBy(()->prepare(changed)).isInstanceOf(BusinessException.class);assertThat(qty("locked_qty")).isEqualTo(2);}
 @Test void adminCompareAndSetRespectsOccupancy(){reserve(50);assertThatThrownBy(()->tx.executeWithoutResult(s->owner.setTotal(new SetTotal(1L,day,0,1)))).isInstanceOf(BusinessException.class);assertThatThrownBy(()->tx.executeWithoutResult(s->owner.setTotal(new SetTotal(1L,day,2,0)))).isInstanceOf(BusinessException.class);tx.executeWithoutResult(s->owner.setTotal(new SetTotal(1L,day,2,1)));assertThat(qty("total_qty")).isEqualTo(5);}
 @Test void missingCapacityCannotChangeStock(){when(capacity.totalRooms(1L)).thenThrow(new BusinessException(503,"unavailable"));assertThatThrownBy(()->tx.executeWithoutResult(s->owner.setTotal(new SetTotal(1L,day,1,0)))).isInstanceOf(BusinessException.class);assertThat(qty("total_qty")).isEqualTo(4);}
}

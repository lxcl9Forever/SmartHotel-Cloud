package com.smarthotel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarthotel.dto.booking.CreateBookingRequest;
import com.smarthotel.entity.BookingOrder;
import com.smarthotel.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real database transactions and competing connections; Redis failures are injected at the boundary. */
class InventoryTransactionsTest {
 JdbcTemplate jdbc;TransactionTemplate tx;InventoryProjection projection;InventoryCommands commands;BookingRequests requests;
 StringRedisTemplate redis;Map<String,String> cache=new ConcurrentHashMap<>();AtomicBoolean outage=new AtomicBoolean();
 LocalDate start=LocalDate.of(2030,1,2),end=start.plusDays(2);
 @BeforeEach void setup() throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
  var manager=new DataSourceTransactionManager(ds);jdbc=spy(new JdbcTemplate(ds));tx=new TransactionTemplate(manager);
  jdbc.execute("CREATE TABLE room_type(room_type_id BIGINT PRIMARY KEY)");jdbc.update("INSERT INTO room_type VALUES(1)");
  jdbc.execute("CREATE TABLE booking_order(order_id BIGINT PRIMARY KEY,order_status VARCHAR(32),room_type_id BIGINT,room_count INT)");
  jdbc.execute("CREATE TABLE room_inventory(inventory_id BIGINT AUTO_INCREMENT PRIMARY KEY,room_type_id BIGINT,inventory_date DATE,total_qty INT,locked_qty INT,sold_qty INT,update_time DATETIME,UNIQUE(room_type_id,inventory_date))");
  String ddl=Files.readString(Path.of("../database/17_inventory_operations.sql")).replace("USE smart_hotel_cloud;","");
  for(String sql:ddl.split(";"))if(!sql.isBlank())jdbc.execute(sql);
  for(int i=0;i<2;i++)jdbc.update("INSERT INTO room_inventory(room_type_id,inventory_date,total_qty,locked_qty,sold_qty) VALUES(1,?,1,0,0)",start.plusDays(i));
  redis=mock(StringRedisTemplate.class);
  when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class))).thenAnswer(inv->{
   if(outage.get())throw new RedisConnectionFailureException("injected outage");
   java.util.List<String> keys=inv.getArgument(1);Object[] values=(Object[])inv.getRawArguments()[2];
   long version=Long.parseLong(values[0].toString());String previous=cache.get(keys.get(1));
   if(previous!=null&&Long.parseLong(previous)>version)return 0L;
   cache.put(keys.get(0),values[1].toString());cache.put(keys.get(1),String.valueOf(version));return 1L;
  });
  projection=new LocalInventoryProjection(jdbc,redis,manager);commands=new LocalInventoryCommands(jdbc,projection);
  requests=new BookingRequests(jdbc,new ObjectMapper().registerModule(new JavaTimeModule()));
 }
 BookingOrder order(long id){jdbc.update("INSERT INTO booking_order VALUES(?,'WAIT_PAY',1,1)",id);BookingOrder o=new BookingOrder();o.setOrderId(id);o.setRoomTypeId(1L);o.setRoomCount(1);o.setCheckInDate(start);o.setCheckOutDate(end);return o;}
 int n(String sql){return jdbc.queryForObject(sql,Integer.class);}
 String key(){return "hotel:inventory:1:"+start;}
 @Test void reserveConfirmAndRefundAreIdempotentAcrossTransactions(){
  var o=order(1);tx.executeWithoutResult(s->commands.reserve(o));tx.executeWithoutResult(s->commands.reserve(o));
  assertThat(n("SELECT SUM(locked_qty) FROM room_inventory")).isEqualTo(2);assertThat(cache.get(key())).isEqualTo("0");
  tx.executeWithoutResult(s->{commands.confirm(o);jdbc.update("UPDATE booking_order SET order_status='CONFIRMED' WHERE order_id=1");});
  tx.executeWithoutResult(s->commands.confirm(o));assertThat(n("SELECT SUM(sold_qty) FROM room_inventory")).isEqualTo(2);
  tx.executeWithoutResult(s->{commands.refund(o);jdbc.update("UPDATE booking_order SET order_status='REFUNDED' WHERE order_id=1");});
  tx.executeWithoutResult(s->commands.refund(o));assertThat(n("SELECT SUM(locked_qty+sold_qty) FROM room_inventory")).isZero();assertThat(cache.get(key())).isEqualTo("1");
 }
 @Test void cancelAndTimeoutUseOneReleaseKey(){
  var o=order(2);tx.executeWithoutResult(s->commands.reserve(o));
  tx.executeWithoutResult(s->{commands.cancel(o);jdbc.update("UPDATE booking_order SET order_status='CLOSED' WHERE order_id=2");});
  tx.executeWithoutResult(s->commands.cancel(o));assertThat(cache.get(key())).isEqualTo("1");assertThat(n("SELECT COUNT(*) FROM inventory_operation")).isEqualTo(2);
 }
 @Test void laterCallerFailureRollsBackStockLedgerQueueAndRequestBinding(){
  var o=order(3);var request=new CreateBookingRequest();request.setRequestId("attempt-3");request.setRoomTypeId(1L);
  assertThatThrownBy(()->tx.executeWithoutResult(s->{requests.begin(9L,request);commands.reserve(o);requests.complete(9L,request,3L);throw new IllegalStateException("payment/order persistence failed");})).isInstanceOf(IllegalStateException.class);
  assertThat(n("SELECT SUM(locked_qty) FROM room_inventory")).isZero();assertThat(n("SELECT COUNT(*) FROM inventory_operation")).isZero();
  assertThat(n("SELECT COUNT(*) FROM inventory_cache_sync")).isZero();assertThat(n("SELECT COUNT(*) FROM booking_request")).isZero();assertThat(cache).isEmpty();
 }
 @Test void insufficientOneNightDoesNotPartiallyReserveAnyDate(){
  var o=order(4);jdbc.update("UPDATE room_inventory SET total_qty=0 WHERE inventory_date=?",start.plusDays(1));
  assertThatThrownBy(()->tx.executeWithoutResult(s->commands.reserve(o))).isInstanceOf(BusinessException.class);
  assertThat(n("SELECT SUM(locked_qty) FROM room_inventory")).isZero();assertThat(n("SELECT COUNT(*) FROM inventory_operation")).isZero();
 }
 @Test void ambiguousRedisAcknowledgementIsRetriedAsAbsoluteLatestValue(){
  var o=order(5);var failOnce=new AtomicBoolean(true);
  doAnswer(inv->{if(failOnce.getAndSet(false))throw new DataAccessResourceFailureException("lost database ack");return inv.callRealMethod();})
   .when(jdbc).update(startsWith("UPDATE inventory_cache_sync SET state='DONE'"),any(Object[].class));
  tx.executeWithoutResult(s->commands.reserve(o));assertThat(cache.get(key())).isEqualTo("0");
  // A repeated SET after a lost ack never adds a second release or a second deduction.
  jdbc.update("UPDATE inventory_cache_sync SET next_attempt_at=CURRENT_TIMESTAMP");projection.retryPending();projection.retryPending();assertThat(cache.get(key())).isEqualTo("0");
  tx.executeWithoutResult(s->commands.cancel(o));projection.retryPending();assertThat(cache.get(key())).isEqualTo("1");
 }
 @Test void redisOutageDoesNotLoseCommittedStockOrPendingWork(){
  var o=order(6);outage.set(true);tx.executeWithoutResult(s->commands.reserve(o));
  assertThat(n("SELECT SUM(locked_qty) FROM room_inventory")).isEqualTo(2);assertThat(n("SELECT COUNT(*) FROM inventory_cache_sync WHERE state='PENDING'")).isEqualTo(2);
  outage.set(false);jdbc.update("UPDATE inventory_cache_sync SET next_attempt_at=CURRENT_TIMESTAMP");projection.retryPending();projection.retryPending();assertThat(cache.get(key())).isEqualTo("0");assertThat(n("SELECT COUNT(*) FROM inventory_cache_sync WHERE state='PENDING'")).isZero();
 }
 @Test void concurrentLastRoomHasExactlyOneWinner()throws Exception {
  var orders=new ArrayList<BookingOrder>();for(int i=10;i<16;i++)orders.add(order(i));
  var pool=Executors.newFixedThreadPool(6);var gate=new CountDownLatch(1);var wins=new AtomicInteger();
  try {var tasks=new ArrayList<Future<?>>();for(var o:orders)tasks.add(pool.submit(()->{try{gate.await();tx.executeWithoutResult(s->commands.reserve(o));wins.incrementAndGet();}catch(BusinessException expected){}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}));gate.countDown();for(var task:tasks)task.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
  assertThat(wins.get()).isEqualTo(1);assertThat(n("SELECT SUM(locked_qty) FROM room_inventory")).isEqualTo(2);assertThat(n("SELECT COUNT(*) FROM inventory_operation")).isEqualTo(1);
 }
 @Test void duplicateRequestReturnsBoundOrderAndPayloadReuseIsRejected(){
  var req=new CreateBookingRequest();req.setRequestId("same-intent");req.setRoomTypeId(1L);order(20);
  tx.executeWithoutResult(s->{assertThat(requests.begin(9L,req)).isNull();requests.complete(9L,req,20L);});
  tx.executeWithoutResult(s->assertThat(requests.begin(9L,req)).isEqualTo(20L));
  req.setRoomTypeId(2L);assertThatThrownBy(()->tx.executeWithoutResult(s->requests.begin(9L,req))).isInstanceOf(BusinessException.class);
  tx.executeWithoutResult(s->assertThat(requests.begin(10L,req)).isNull());
 }
 @Test void sameOperationKeyCannotChangeItsParameters(){
  var o=order(21);tx.executeWithoutResult(s->commands.reserve(o));o.setRoomCount(2);
  assertThatThrownBy(()->tx.executeWithoutResult(s->commands.reserve(o))).isInstanceOf(BusinessException.class);
  assertThat(n("SELECT SUM(locked_qty) FROM room_inventory")).isEqualTo(2);
 }
}

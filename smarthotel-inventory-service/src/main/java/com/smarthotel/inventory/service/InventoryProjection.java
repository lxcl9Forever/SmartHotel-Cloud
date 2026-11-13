package com.smarthotel.inventory.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;

/** Durable coalescing outbox; Redis is a projection, not an independent stock authority. */
@Service @Slf4j
public class InventoryProjection {
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> APPLY=script();
    private static org.springframework.data.redis.core.script.DefaultRedisScript<Long> script() {
        var script=new org.springframework.data.redis.core.script.DefaultRedisScript<Long>();
        script.setLocation(new org.springframework.core.io.ClassPathResource("lua/project_inventory.lua"));script.setResultType(Long.class);return script;
    }
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transaction;
    private final java.util.concurrent.Executor executor;
    public InventoryProjection(JdbcTemplate jdbc,StringRedisTemplate redis,PlatformTransactionManager manager) {
        this(jdbc,redis,manager,Runnable::run);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public InventoryProjection(JdbcTemplate jdbc,StringRedisTemplate redis,PlatformTransactionManager manager,
            @org.springframework.beans.factory.annotation.Qualifier("inventoryProjectionExecutor") java.util.concurrent.Executor executor) {
        this.jdbc=jdbc;this.redis=redis;transaction=new TransactionTemplate(manager);
        this.executor=executor;
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Transactional
    public void enqueue(Long type,LocalDate from,LocalDate to) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Inventory outbox requires a transaction");
        for(LocalDate day=from;day.isBefore(to);day=day.plusDays(1)) {
            jdbc.update("INSERT INTO inventory_cache_sync(room_type_id,inventory_date,state,revision) VALUES(?,?,'PENDING',1) ON DUPLICATE KEY UPDATE state='PENDING',revision=revision+1,next_attempt_at=CURRENT_TIMESTAMP",type,day);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { executor.execute(()->synchronizeRange(type,from,to)); }
                catch(RuntimeException rejected) { log.warn("Immediate inventory sync deferred to durable queue"); }
            }
        });
    }
    public boolean synchronizeRange(Long type,LocalDate from,LocalDate to) {
        for(LocalDate day=from;day.isBefore(to);day=day.plusDays(1))if(!syncOne(type,day))return false;
        return true;
    }
    public boolean syncOne(Long type,LocalDate day) {
        try {
            return Boolean.TRUE.equals(transaction.execute(status->{
                // Keep source-row lock until SET and queue commit. A delayed worker reads the latest
                // database value instead of applying an old delta or overwriting a newer snapshot.
                var rows=jdbc.queryForList("SELECT total_qty,locked_qty,sold_qty FROM room_inventory WHERE room_type_id=? AND inventory_date=? FOR UPDATE",type,day);
                if(rows.size()!=1)return false;
                var row=rows.get(0);int available=((Number)row.get("total_qty")).intValue()-((Number)row.get("locked_qty")).intValue()-((Number)row.get("sold_qty")).intValue();
                if(available<0)throw new IllegalStateException("Negative authoritative stock");
                jdbc.update("INSERT INTO inventory_cache_sync(room_type_id,inventory_date,state,revision) VALUES(?,?,'PENDING',1) ON DUPLICATE KEY UPDATE room_type_id=room_type_id",type,day);
                Long revision=jdbc.queryForObject("SELECT revision FROM inventory_cache_sync WHERE room_type_id=? AND inventory_date=? FOR UPDATE",Long.class,type,day);
                Long applied=redis.execute(APPLY,java.util.List.of("hotel:inventory:"+type+":"+day,"hotel:inventory:projection-version:"+type+":"+day),String.valueOf(revision),String.valueOf(available));
                if(!Long.valueOf(1).equals(applied))throw new IllegalStateException("Redis projection version is ahead of the database; reconcile instead of overwriting");
                jdbc.update("UPDATE inventory_cache_sync SET state='DONE',last_error=NULL,synced_at=CURRENT_TIMESTAMP WHERE room_type_id=? AND inventory_date=?",type,day);
                return true;
            }));
        } catch(RuntimeException error) {
            // No credentials/customer data in the diagnostic. Queue stays durable and PENDING.
            log.warn("Inventory projection pending for type {} date {} ({})",type,day,error.getClass().getSimpleName());
            try { transaction.executeWithoutResult(status->jdbc.update("UPDATE inventory_cache_sync SET attempts=attempts+1,last_error=?,next_attempt_at=? WHERE room_type_id=? AND inventory_date=? AND state='PENDING'",error.getClass().getSimpleName(),java.time.LocalDateTime.now().plusSeconds(10),type,day)); }
            catch(RuntimeException unavailable){ /* The committed PENDING row remains the recovery point. */ }
            return false;
        }
    }
    @Transactional public void rebuildAll() {
        jdbc.update("INSERT INTO inventory_cache_sync(room_type_id,inventory_date,state,revision) SELECT room_type_id,inventory_date,'PENDING',1 FROM room_inventory ON DUPLICATE KEY UPDATE state='PENDING',revision=revision+1,next_attempt_at=CURRENT_TIMESTAMP");
    }
    @Scheduled(fixedDelayString="${smarthotel.inventory.retry-delay-ms:5000}")
    public void retryPending() {
        try {
            for(var row:jdbc.queryForList("SELECT room_type_id,inventory_date FROM inventory_cache_sync WHERE state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY next_attempt_at,inventory_date,room_type_id LIMIT 100")) {
                syncOne(((Number)row.get("room_type_id")).longValue(),LocalDate.parse(row.get("inventory_date").toString()));
            }
        }catch(RuntimeException error){log.warn("Inventory projection scan will retry ({})",error.getClass().getSimpleName());}
    }
}

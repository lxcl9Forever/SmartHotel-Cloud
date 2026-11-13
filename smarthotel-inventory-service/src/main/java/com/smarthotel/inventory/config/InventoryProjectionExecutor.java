package com.smarthotel.inventory.config;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;
@Configuration("inventoryProjectionExecutorConfiguration")
public class InventoryProjectionExecutor {
 @Bean("inventoryProjectionExecutor")
 public ThreadPoolTaskExecutor inventoryExecutor() {
  var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);
  executor.setQueueCapacity(64);executor.setThreadNamePrefix("inventory-projection-");
  // Losing a wakeup does not lose work: the database outbox is scanned periodically.
  executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
  executor.setWaitForTasksToCompleteOnShutdown(true);executor.setAwaitTerminationSeconds(5);
  return executor;
 }
}

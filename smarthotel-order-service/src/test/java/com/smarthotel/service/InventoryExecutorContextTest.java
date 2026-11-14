package com.smarthotel.service;
import com.smarthotel.config.InventoryProjectionExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
class InventoryExecutorContextTest {
 @Test void executorAndConfigurationHaveDistinctBeanNamesAndExecuteWork()throws Exception {
  try(var context=new AnnotationConfigApplicationContext(InventoryProjectionExecutor.class)) {
   var completed=new CountDownLatch(1);
   context.getBean("inventoryProjectionExecutor",Executor.class).execute(completed::countDown);
   assertThat(completed.await(2,TimeUnit.SECONDS)).isTrue();
  }
 }
}

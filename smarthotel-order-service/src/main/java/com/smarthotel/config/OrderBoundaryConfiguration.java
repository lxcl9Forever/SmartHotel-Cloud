package com.smarthotel.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
/** Reject unsupported legacy mode even when launched directly from IDEA. */
@Configuration(proxyBeanMethods=false)
public class OrderBoundaryConfiguration {
 @Bean InitializingBean orderInventoryBoundary(@Value("${smarthotel.inventory.mode:remote}") String mode){
  return ()->{if(!"remote".equals(mode))throw new IllegalStateException("Order workflows require remote inventory; legacy local inventory mode is no longer supported");};
 }
}

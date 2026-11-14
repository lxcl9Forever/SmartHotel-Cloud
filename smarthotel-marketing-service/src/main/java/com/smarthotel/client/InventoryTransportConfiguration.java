package com.smarthotel.client;
import feign.*;
import org.springframework.context.annotation.Bean;
/** Explicitly scoped to the inventory Feign context, not a global component. */
public class InventoryTransportConfiguration {
 @Bean public Retryer inventoryRetryer(){return Retryer.NEVER_RETRY;}
 @Bean public Request.Options inventoryOptions(){return new Request.Options(1500,2000);}
}

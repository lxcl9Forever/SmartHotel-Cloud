package com.smarthotel.integration;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.test.util.ReflectionTestUtils;
import com.smarthotel.exception.BusinessException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class InternalDiscoveryTest {
 @Test void discoveryResolvesEachAttemptAndPreservesPath(){
  var http=new InternalHttp("synthetic-private-test-token");var load=mock(LoadBalancerClient.class);
  ReflectionTestUtils.setField(http,"balancer",load);
  when(load.choose("smarthotel-order-service")).thenReturn(new DefaultServiceInstance("a","smarthotel-order-service","127.0.0.1",18082,false),new DefaultServiceInstance("b","smarthotel-order-service","127.0.0.1",18083,false));
  assertEquals("http://127.0.0.1:18082/internal/v1/orders/2",http.resolve("lb://smarthotel-order-service/internal/v1/orders/2"));
  assertEquals("http://127.0.0.1:18083/internal/v1/orders/2",http.resolve("lb://smarthotel-order-service/internal/v1/orders/2"));
 }
 @Test void unavailableDiscoveryHasNoStaticFallback(){
  var http=new InternalHttp("synthetic-private-test-token");
  assertThrows(BusinessException.class,()->http.resolve("lb://smarthotel-order-service/internal/v1/orders/2"));
  assertEquals("http://order:8082/internal/v1/orders/2",http.resolve("http://order:8082/internal/v1/orders/2"));
 }
}

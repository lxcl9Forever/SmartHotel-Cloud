package com.smarthotel.config;
import com.smarthotel.integration.DomainOwnership;
import org.springframework.context.annotation.*;
import java.util.*;
@Configuration public class DomainOwnershipConfiguration {
  @Bean DomainOwnership domainOwnership(@org.springframework.beans.factory.annotation.Value("${ORDER_SERVICE_URL:http://127.0.0.1:8082}") String order,@org.springframework.beans.factory.annotation.Value("${STATISTICS_SERVICE_URL:http://127.0.0.1:8090}") String statistics) {
    return new DomainOwnership() {
      public String domain() {
        return "payment";
      }
      public Map<String,String> ownedTables() {
        return java.util.Map.ofEntries(java.util.Map.entry("payment","payment_id"),java.util.Map.entry("refund_record","refund_id"),java.util.Map.entry("refund_payment_record","refund_payment_id"));
      }
      public Map<String,String> projections() {
        return java.util.Map.ofEntries(java.util.Map.entry("order:booking_order","order_snapshot"),java.util.Map.entry("stay:stay_charge","stay_charge_snapshot"));
      }
      public Map<String,String> targets() {
        return java.util.Map.ofEntries(java.util.Map.entry("order",order),java.util.Map.entry("statistics",statistics));
      }
      public Set<String> audience(String table) {
        return table.equals("booking_guest")?Set.of("stay"):targets().keySet();
      }
    }
    ;
  }
}

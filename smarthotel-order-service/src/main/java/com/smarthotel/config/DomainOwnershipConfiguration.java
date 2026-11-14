package com.smarthotel.config;
import com.smarthotel.integration.DomainOwnership;
import org.springframework.context.annotation.*;
import java.util.*;
@Configuration public class DomainOwnershipConfiguration {
  @Bean DomainOwnership domainOwnership(@org.springframework.beans.factory.annotation.Value("${MARKETING_SERVICE_URL:http://127.0.0.1:8086}") String marketing,@org.springframework.beans.factory.annotation.Value("${PAYMENT_SERVICE_URL:http://127.0.0.1:8087}") String payment,@org.springframework.beans.factory.annotation.Value("${STAY_SERVICE_URL:http://127.0.0.1:8089}") String stay,@org.springframework.beans.factory.annotation.Value("${STATISTICS_SERVICE_URL:http://127.0.0.1:8090}") String statistics) {
    return new DomainOwnership() {
      public String domain() {
        return "order";
      }
      public Map<String,String> ownedTables() {
        return java.util.Map.ofEntries(java.util.Map.entry("booking_order","order_id"),java.util.Map.entry("booking_guest","guest_id"));
      }
      public Map<String,String> projections() {
        return java.util.Map.ofEntries(java.util.Map.entry("payment:payment","payment_snapshot"),java.util.Map.entry("payment:refund_record","refund_snapshot"),java.util.Map.entry("stay:room","room_snapshot"));
      }
      public Map<String,String> targets() {
        return java.util.Map.ofEntries(java.util.Map.entry("marketing",marketing),java.util.Map.entry("payment",payment),java.util.Map.entry("stay",stay),java.util.Map.entry("statistics",statistics));
      }
      public Set<String> audience(String table) {
        return table.equals("booking_guest")?Set.of("stay"):targets().keySet();
      }
    }
    ;
  }
}

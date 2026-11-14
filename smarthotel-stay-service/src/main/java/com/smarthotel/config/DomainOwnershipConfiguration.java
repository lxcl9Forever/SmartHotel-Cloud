package com.smarthotel.config;
import com.smarthotel.integration.DomainOwnership;
import org.springframework.context.annotation.*;
import java.util.*;
@Configuration public class DomainOwnershipConfiguration {
  @Bean DomainOwnership domainOwnership(@org.springframework.beans.factory.annotation.Value("${ORDER_SERVICE_URL:http://127.0.0.1:8082}") String order,@org.springframework.beans.factory.annotation.Value("${PAYMENT_SERVICE_URL:http://127.0.0.1:8087}") String payment,@org.springframework.beans.factory.annotation.Value("${STATISTICS_SERVICE_URL:http://127.0.0.1:8090}") String statistics) {
    return new DomainOwnership() {
      public String domain() {
        return "stay";
      }
      public Map<String,String> ownedTables() {
        return java.util.Map.ofEntries(java.util.Map.entry("room","room_id"),java.util.Map.entry("room_assignment","assignment_id"),java.util.Map.entry("room_status_log","log_id"),java.util.Map.entry("check_in_record","check_in_id"),java.util.Map.entry("check_out_record","check_out_id"),java.util.Map.entry("stay_charge","operation_id"));
      }
      public Map<String,String> projections() {
        return java.util.Map.ofEntries(java.util.Map.entry("order:booking_order","order_snapshot"),java.util.Map.entry("order:booking_guest","guest_snapshot"));
      }
      public Map<String,String> targets() {
        return java.util.Map.ofEntries(java.util.Map.entry("order",order),java.util.Map.entry("payment",payment),java.util.Map.entry("statistics",statistics));
      }
      public Set<String> audience(String table) {
        return table.equals("booking_guest")?Set.of("stay"):targets().keySet();
      }
    }
    ;
  }
}

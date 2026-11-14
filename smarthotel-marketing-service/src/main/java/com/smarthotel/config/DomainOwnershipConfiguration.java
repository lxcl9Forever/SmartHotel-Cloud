package com.smarthotel.config;
import com.smarthotel.integration.DomainOwnership;
import org.springframework.context.annotation.*;
import java.util.*;
@Configuration public class DomainOwnershipConfiguration {
  @Bean DomainOwnership domainOwnership() {
    return new DomainOwnership() {
      public String domain() {
        return "marketing";
      }
      public Map<String,String> ownedTables() {
        return java.util.Map.ofEntries(java.util.Map.entry("promotion","id"),java.util.Map.entry("daily_deal","id"),java.util.Map.entry("daily_checkin_reward","day_number"),java.util.Map.entry("customer_checkin_record","id"),java.util.Map.entry("customer_checkin_state","customer_id"),java.util.Map.entry("customer_coupon","id"),java.util.Map.entry("marketing_reservation","operation_id"));
      }
      public Map<String,String> projections() {
        return java.util.Map.of("order:booking_order","order_snapshot");
      }
      public Map<String,String> targets() {
        return java.util.Map.ofEntries();
      }
      public Set<String> audience(String table) {
        return table.equals("booking_guest")?Set.of("stay"):targets().keySet();
      }
    }
    ;
  }
}

package com.smarthotel.config;
import com.smarthotel.integration.DomainOwnership;
import org.springframework.context.annotation.*;
import java.util.*;
@Configuration public class DomainOwnershipConfiguration {
  @Bean DomainOwnership domainOwnership() {
    return new DomainOwnership() {
      public String domain() {
        return "statistics";
      }
      public Map<String,String> ownedTables() {
        return java.util.Map.ofEntries(java.util.Map.entry("hotel_daily_statistics","stat_id"));
      }
      public Map<String,String> projections() {
        return java.util.Map.ofEntries(java.util.Map.entry("order:booking_order","order_snapshot"),java.util.Map.entry("payment:payment","payment_snapshot"),java.util.Map.entry("stay:check_in_record","check_in_record_snapshot"),java.util.Map.entry("stay:check_out_record","check_out_record_snapshot"),java.util.Map.entry("stay:room_assignment","room_assignment_snapshot"));
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

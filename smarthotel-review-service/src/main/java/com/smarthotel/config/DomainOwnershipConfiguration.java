package com.smarthotel.config;
import com.smarthotel.integration.DomainOwnership;import org.springframework.context.annotation.*;import java.util.*;
@Configuration public class DomainOwnershipConfiguration {
 @Bean DomainOwnership domainOwnership(){return new DomainOwnership(){
 public String domain(){return "review";}
 public Map<String,String> ownedTables(){return java.util.Map.ofEntries(java.util.Map.entry("order_review","review_id"));}
 public Map<String,String> projections(){return java.util.Map.ofEntries();}
 public Map<String,String> targets(){return java.util.Map.ofEntries();}
 public Set<String> audience(String table){return table.equals("booking_guest")?Set.of("stay"):targets().keySet();}
 };}
}

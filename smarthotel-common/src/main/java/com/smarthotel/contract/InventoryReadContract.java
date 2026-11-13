package com.smarthotel.contract;
import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
/** Version-one wire contract, deliberately independent of persistence annotations and entities. */
public final class InventoryReadContract {
 private InventoryReadContract(){}
 public record Query(List<Long> ids,LocalDate from,LocalDate to){}
 /** Preserve the existing JSON fields while decoupling service callers from database entities. */
 @Data public static class Snapshot {
  private Long inventoryId;
  private Long roomTypeId;
  private LocalDate inventoryDate;
  private Integer totalQty;
  private Integer lockedQty;
  private Integer soldQty;
  private Integer version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
 }
}

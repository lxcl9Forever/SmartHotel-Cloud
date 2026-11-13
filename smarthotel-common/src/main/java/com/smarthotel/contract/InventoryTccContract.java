package com.smarthotel.contract;
import java.time.LocalDate;
public final class InventoryTccContract {
 private InventoryTccContract(){}
 public record Command(String attemptId,Long orderId,String kind,Long roomTypeId,LocalDate from,LocalDate to,Integer count) {
  public String operationKey(){return (kind.equals("EXTEND")?"EXTEND:"+to:kind.equals("RESERVE_PAID")?"RESERVE":kind)+":"+orderId;}
  public String signature(){return roomTypeId+"|"+from+"|"+to+"|"+count;}
 }
 public record Reply(String phase,boolean replayed){}
 public record Decision(Command command,String decision){}
 public record Range(Long roomTypeId,LocalDate from,LocalDate to){}
 public record SetTotal(Long roomTypeId,LocalDate date,Integer totalQty,Integer expectedVersion){}
}

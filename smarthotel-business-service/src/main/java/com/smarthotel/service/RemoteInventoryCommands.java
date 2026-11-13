package com.smarthotel.service;
import com.smarthotel.contract.InventoryTccContract.Command;
import com.smarthotel.entity.BookingOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.time.LocalDate;
import java.util.UUID;
@Service @RequiredArgsConstructor @ConditionalOnProperty(name="smarthotel.inventory.mode",havingValue="remote")
public class RemoteInventoryCommands implements InventoryCommands {
 private final InventoryTccCoordinator coordinator;
 private String zeroKey(Long id){return "inventory-zero-paid:"+id;}
 private void prepare(BookingOrder o,String kind,LocalDate from,LocalDate to){coordinator.prepare(new Command(UUID.randomUUID().toString(),o.getOrderId(),kind,o.getRoomTypeId(),from,to,o.getRoomCount()));}
 @Transactional(propagation=Propagation.MANDATORY) public void reserve(BookingOrder o){
  boolean zero=o.getTotalAmount()!=null&&o.getTotalAmount().signum()==0;
  prepare(o,zero?"RESERVE_PAID":"RESERVE",o.getCheckInDate(),o.getCheckOutDate());
  if(zero){String key=zeroKey(o.getOrderId());TransactionSynchronizationManager.bindResource(key,Boolean.TRUE);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){TransactionSynchronizationManager.unbindResourceIfPossible(key);}});}
 }
 @Transactional(propagation=Propagation.MANDATORY) public void confirm(BookingOrder o){if(!TransactionSynchronizationManager.hasResource(zeroKey(o.getOrderId())))prepare(o,"CONFIRM",o.getCheckInDate(),o.getCheckOutDate());}
 @Transactional(propagation=Propagation.MANDATORY) public void cancel(BookingOrder o){prepare(o,"RELEASE_LOCKED",o.getCheckInDate(),o.getCheckOutDate());}
 @Transactional(propagation=Propagation.MANDATORY) public void refund(BookingOrder o){prepare(o,"RELEASE_SOLD",o.getCheckInDate(),o.getCheckOutDate());}
 @Transactional(propagation=Propagation.MANDATORY) public void extend(BookingOrder o,LocalDate until){prepare(o,"EXTEND",o.getCheckOutDate(),until);}
}

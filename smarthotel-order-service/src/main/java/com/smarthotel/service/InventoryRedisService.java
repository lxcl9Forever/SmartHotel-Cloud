package com.smarthotel.service;
import java.time.LocalDate;
/** Cache maintenance delegates all writes to the authoritative inventory owner. */
public interface InventoryRedisService {
 void initInventory();
 Integer getAvailableQty(Long roomTypeId,LocalDate date);
 void syncInventory(Long roomTypeId,LocalDate date,Integer availableQty);
}

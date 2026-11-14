package com.smarthotel.service;
import java.time.LocalDate;
public interface InventoryProjection {
 void enqueue(Long type,LocalDate from,LocalDate to);boolean synchronizeRange(Long type,LocalDate from,LocalDate to);boolean syncOne(Long type,LocalDate day);void retryPending();void rebuildAll();
}

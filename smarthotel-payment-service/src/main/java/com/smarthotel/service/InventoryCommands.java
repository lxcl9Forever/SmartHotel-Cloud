package com.smarthotel.service;
import com.smarthotel.entity.BookingOrder;
import java.time.LocalDate;
public interface InventoryCommands {
 void reserve(BookingOrder order);void confirm(BookingOrder order);void cancel(BookingOrder order);void refund(BookingOrder order);void extend(BookingOrder order,LocalDate until);
}

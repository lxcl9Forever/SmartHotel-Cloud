package com.smarthotel.service;
import com.smarthotel.integration.events.DomainEvents;import org.springframework.stereotype.Service;
@Service public class DailyCheckinService {
 private final DomainEvents events;public DailyCheckinService(DomainEvents events){this.events=events;}
 public void release(long orderId){events.emitCurrent("booking_order",String.valueOf(orderId));}
 public void markUsed(long orderId){events.emitCurrent("booking_order",String.valueOf(orderId));}
}

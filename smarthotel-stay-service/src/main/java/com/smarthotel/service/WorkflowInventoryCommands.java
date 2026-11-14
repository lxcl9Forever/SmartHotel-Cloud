package com.smarthotel.service;
import com.smarthotel.entity.BookingOrder;import com.smarthotel.integration.workflow.WorkflowContext;import org.springframework.stereotype.Service;import java.time.LocalDate;import java.util.Map;
@Service public class WorkflowInventoryCommands implements InventoryCommands {
 public void reserve(BookingOrder order){throw new UnsupportedOperationException("Only order domain reserves initial inventory");}
 public void confirm(BookingOrder order){WorkflowContext.effect("CONFIRM",null,Map.of());}
 public void cancel(BookingOrder order){throw new UnsupportedOperationException("Only order domain cancels bookings");}
 public void refund(BookingOrder order){WorkflowContext.effect("REFUND",null,Map.of());}
 public void extend(BookingOrder order,LocalDate until){WorkflowContext.effect("EXTEND",until.toString(),Map.of());}
}

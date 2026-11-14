package com.smarthotel.integration.workflow;
import com.smarthotel.contract.order.OrderSnapshot;import com.smarthotel.vo.BookingGuestVO;import java.util.*;
public final class WorkflowContract {
 private WorkflowContract(){}
 public record Claim(String operationId,String domain,String action,Long orderId,String requestHash){}
 public record Claimed(OrderSnapshot order,List<BookingGuestVO> guests){}
 public record Complete(Claim claim,Map<String,Object> patch,List<Effect> effects){}
 public record Effect(String kind,String until,Map<String,Object> values){}
 public record Abort(String operationId,String domain,Long orderId){}
}

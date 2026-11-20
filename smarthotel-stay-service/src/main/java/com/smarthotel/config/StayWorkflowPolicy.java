package com.smarthotel.config;
import com.smarthotel.integration.workflow.WorkflowPolicy;
import com.smarthotel.dto.checkin.CheckInRequest;
import com.smarthotel.dto.checkout.CheckOutRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.stereotype.Component;
@Component public class StayWorkflowPolicy implements WorkflowPolicy {
  public Operation resolve(ProceedingJoinPoint call) {
    String type=call.getTarget().getClass().getSimpleName(),method=call.getSignature().getName();
    Object[] args=call.getArgs();
    if(type.equals("CheckInServiceImpl")&&method.equals("checkIn"))return new Operation("CHECK_IN",((CheckInRequest)args[1]).getOrderId(),"single");
    if(type.equals("CheckOutServiceImpl")&&method.equals("checkOut"))return new Operation("CHECK_OUT",((CheckOutRequest)args[1]).getOrderId(),"single");
    if(type.equals("StaffOrderServiceImpl")&&method.equals("completeOrder"))return new Operation("COMPLETE",(Long)args[0],"single");
    if(type.equals("StaffOrderServiceImpl")&&method.equals("markNoShow"))return new Operation("NO_SHOW",(Long)args[0],"single");
    return null;
  }
}

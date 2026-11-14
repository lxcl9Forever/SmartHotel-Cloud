package com.smarthotel.integration.workflow;
import org.aspectj.lang.ProceedingJoinPoint;
public interface WorkflowPolicy {
 record Operation(String action,Long orderId,String key){}
 Operation resolve(ProceedingJoinPoint call);
}

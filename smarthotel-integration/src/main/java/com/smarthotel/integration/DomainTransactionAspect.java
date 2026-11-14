package com.smarthotel.integration;
import com.smarthotel.integration.events.DomainEvents;
import com.smarthotel.integration.workflow.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Aspect @Component @Order(-1000) public class DomainTransactionAspect {
  private final DomainEvents events;
  private final WorkflowCoordinator workflows;
  private final ObjectProvider<WorkflowPolicy> policy;
  private final TransactionTemplate tx;
  private final ThreadLocal<Boolean> nested=new ThreadLocal<>();
  public DomainTransactionAspect(DomainEvents events,WorkflowCoordinator workflows,ObjectProvider<WorkflowPolicy> policy,PlatformTransactionManager manager) {
    this.events=events;
    this.workflows=workflows;
    this.policy=policy;
    this.tx=new TransactionTemplate(manager);
  }
  @Around("@annotation(org.springframework.transaction.annotation.Transactional) && within(com.smarthotel..*) && !within(com.smarthotel.integration..*)") public Object transact(ProceedingJoinPoint call)throws Throwable {
    if(Boolean.TRUE.equals(nested.get()))return call.proceed();
    nested.set(true);
    try {
      var p=policy.getIfAvailable();
      var operation=p==null?null:p.resolve(call);
      if(operation!=null)return workflows.execute(call,operation);
      try {
        return tx.execute(status-> {
          var before=events.capture();try {
            Object value=call.proceed();events.changed(before);return value;
          }
          catch(Throwable failure) {
            throw new Wrapped(failure);
          }
        }
        );
      }
      catch(Wrapped failure) {
        throw failure.getCause();
      }
    }
    finally {
      nested.remove();
    }
  }
  private static class Wrapped extends RuntimeException {
    Wrapped(Throwable cause) {
      super(cause);
    }
  }
}

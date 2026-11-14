package com.smarthotel.config;
import com.smarthotel.integration.workflow.WorkflowPolicy;
import com.smarthotel.exception.BusinessException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
@Component public class PaymentWorkflowPolicy implements WorkflowPolicy {
  private final JdbcTemplate jdbc;
  public PaymentWorkflowPolicy(JdbcTemplate jdbc) {
    this.jdbc=jdbc;
  }
  public Operation resolve(ProceedingJoinPoint call) {
    String type=call.getTarget().getClass().getSimpleName(),method=call.getSignature().getName();
    Object[] args=call.getArgs();
    if(type.equals("MockPaymentService")&&method.equals("pay"))return new Operation("MOCK_PAY",(Long)args[1],"single");
    if(type.equals("RefundServiceImpl")&&method.equals("applyRefund"))return new Operation("REFUND_APPLY",(Long)args[0],String.valueOf(jdbc.queryForObject("SELECT COALESCE(MAX(refund_id),0) FROM refund_record WHERE order_id=?",Long.class,args[0])));
    if(type.equals("AdminRefundServiceImpl")&&(method.equals("approve")||method.equals("reject"))) {
      var ids=jdbc.queryForList("SELECT order_id FROM refund_record WHERE refund_id=?",Long.class,args[0]);
      if(ids.isEmpty())throw new BusinessException(404,"退款申请不存在");
      return new Operation(method.equals("approve")?"REFUND_APPROVE":"REFUND_REJECT",ids.get(0),args[0].toString());
    }
    if(type.equals("PaymentCaptureService")&&method.equals("capture")) {
      var ids=jdbc.queryForList("SELECT order_id FROM payment WHERE payment_no=?",Long.class,args[0]);
      if(ids.isEmpty())throw new BusinessException(404,"支付记录不存在");
      return new Operation("PAYMENT_CAPTURE",ids.get(0),args[0].toString());
    }
    return null;
  }
}

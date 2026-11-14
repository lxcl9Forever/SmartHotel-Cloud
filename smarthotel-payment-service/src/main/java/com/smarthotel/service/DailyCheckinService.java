package com.smarthotel.service;
import com.smarthotel.integration.workflow.WorkflowContext;import org.springframework.stereotype.Service;import java.util.Map;
@Service public class DailyCheckinService {public void markUsed(long orderId){WorkflowContext.effect("COUPON_USED",null,Map.of());}}

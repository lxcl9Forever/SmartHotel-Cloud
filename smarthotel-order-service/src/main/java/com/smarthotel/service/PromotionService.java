package com.smarthotel.service;
import com.smarthotel.integration.InternalHttp;import org.springframework.stereotype.Service;import org.springframework.beans.factory.annotation.Value;import java.math.BigDecimal;
@Service public class PromotionService {
 public record Price(Long promotionId,BigDecimal originalAmount,BigDecimal discountAmount,BigDecimal payableAmount){}
 private final InternalHttp http;private final String url;
 public PromotionService(InternalHttp http,@Value("${MARKETING_SERVICE_URL:http://127.0.0.1:8086}")String url){this.http=http;this.url=url;}
 public Price calculate(BigDecimal amount){return http.post(url+"/internal/v1/marketing/price",amount,Price.class);}
}

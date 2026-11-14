package com.smarthotel.mapper;
import com.smarthotel.client.ReviewClient;import com.smarthotel.exception.BusinessException;import org.springframework.beans.factory.annotation.Value;import org.springframework.stereotype.Component;import java.math.BigDecimal;
@Component public class RoomRatingMapper {
 private final ReviewClient client;private final String token;
 public RoomRatingMapper(ReviewClient client,@Value("${smarthotel.internal.token}") String token){this.client=client;this.token=token;}
 public BigDecimal averageRating(Long id){try{var result=client.rating(token,id);if(result==null)throw new IllegalStateException();return result.average();}catch(RuntimeException e){throw new BusinessException(503,"评价服务暂不可用");}}
}

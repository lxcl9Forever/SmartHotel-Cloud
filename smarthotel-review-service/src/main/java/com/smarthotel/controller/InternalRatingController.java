package com.smarthotel.controller;
import com.smarthotel.mapper.RoomRatingMapper;
import com.smarthotel.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
@RestController public class InternalRatingController {
  private final RoomRatingMapper ratings;
  private final String token;
  public InternalRatingController(RoomRatingMapper ratings,@Value("${smarthotel.internal.token}") String token) {
    this.ratings=ratings;
    this.token=token;
  }
  public record Rating(BigDecimal average) {
  }
  @GetMapping("/internal/v1/reviews/ratings/{id}") public Rating rating(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@PathVariable Long id) {
    if(!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),key.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(403,"内部凭据无效");
    return new Rating(ratings.averageRating(id));
  }
}

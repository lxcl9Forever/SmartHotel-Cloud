package com.smarthotel.controller;
import com.smarthotel.mapper.BookingOrderMapper;
import com.smarthotel.contract.order.OrderSnapshot;
import com.smarthotel.exception.BusinessException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
@RestController public class InternalOrderReadController {
 @org.springframework.beans.factory.annotation.Autowired private com.smarthotel.service.BookingService bookings;
 private final BookingOrderMapper orders;private final byte[] secret;
 public InternalOrderReadController(BookingOrderMapper orders,@Value("${smarthotel.internal.token}") String token){this.orders=orders;this.secret=token.getBytes(StandardCharsets.UTF_8);}
 @GetMapping("/internal/v1/orders/{id}") public OrderSnapshot find(@RequestHeader(value="X-Internal-Token",defaultValue="") String token,@PathVariable Long id){
  if(!MessageDigest.isEqual(secret,token.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(403,"内部凭据无效");
  var entity=orders.selectById(id);if(entity==null)throw new BusinessException(404,"订单不存在");
  var result=new OrderSnapshot();BeanUtils.copyProperties(entity,result);return result;
 }

 @GetMapping("/internal/v1/orders/{id}/customer-view") public com.smarthotel.vo.BookingOrderVO view(@RequestHeader(value="X-Internal-Token",defaultValue="")String token,@PathVariable Long id,@RequestParam String username){
  if(!MessageDigest.isEqual(secret,token.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(403,"内部凭据无效");return bookings.getMyBooking(username,id);
 }
}

package com.smarthotel.hotel;
import com.smarthotel.hotel.mapper.RoomTypeMapper;import com.smarthotel.exception.BusinessException;
import org.springframework.web.bind.annotation.*;import org.springframework.beans.factory.annotation.Value;
import java.security.MessageDigest;import java.nio.charset.StandardCharsets;
@RestController @RequestMapping("/internal/v1/hotel/capacity") public class HotelCapacityController {
 private final RoomTypeMapper types;private final byte[] token;
 public HotelCapacityController(RoomTypeMapper types,@Value("${smarthotel.internal.token}") String secret){if(secret.length()<32)throw new IllegalArgumentException("Invalid internal token");this.types=types;token=secret.getBytes(StandardCharsets.UTF_8);}
 @GetMapping("/{id}") public Integer capacity(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@PathVariable Long id){if(!MessageDigest.isEqual(token,key.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(401,"内部酒店身份验证失败");var type=types.selectById(id);if(type==null)throw new BusinessException(404,"房型不存在");if(type.getTotalRooms()==null||type.getTotalRooms()<0)throw new BusinessException(409,"房型容量异常");return type.getTotalRooms();}
}

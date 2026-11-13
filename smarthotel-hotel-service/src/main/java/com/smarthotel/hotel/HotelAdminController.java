package com.smarthotel.hotel;
import java.util.List;
import java.time.LocalDate;
import com.smarthotel.contract.HotelCatalogContract.*;
import com.smarthotel.contract.InventoryReadContract.Snapshot;
import com.smarthotel.dto.admin.UpdatePriceRequest;
import org.springframework.web.bind.annotation.*;
import com.smarthotel.hotel.service.HotelAdminService;
import com.smarthotel.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
@RestController @RequestMapping("/internal/v1/hotel-admin")
public class HotelAdminController {
 private final HotelAdminService service; private final byte[] token;
 public HotelAdminController(HotelAdminService service,@Value("${smarthotel.internal.token}") String secret){if(secret.length()<32)throw new IllegalArgumentException("Internal token must contain at least 32 characters");this.service=service;this.token=secret.getBytes(StandardCharsets.UTF_8);}
 private <T>T copy(Object source,java.util.function.Supplier<T> factory){var target=factory.get();org.springframework.beans.BeanUtils.copyProperties(source,target);return target;}
 private void auth(String key){if(!MessageDigest.isEqual(token,key.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(401,"内部酒店身份验证失败");}
 @GetMapping("/room-types") public List<TypeSnapshot> listRoomTypes(@RequestHeader(value="X-Internal-Token",defaultValue="") String key){auth(key);return service.listRoomTypes().stream().map(row->copy(row,TypeSnapshot::new)).toList();}
 @GetMapping("/inventories") public List<Snapshot> listInventories(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@RequestParam Long roomTypeId,@RequestParam LocalDate startDate,@RequestParam LocalDate endDate){auth(key);return service.listInventories(roomTypeId,startDate,endDate).stream().map(row->copy(row,Snapshot::new)).toList();}
 @GetMapping("/prices") public List<PriceSnapshot> listPrices(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@RequestParam Long roomTypeId,@RequestParam LocalDate startDate,@RequestParam LocalDate endDate){auth(key);return service.listPrices(roomTypeId,startDate,endDate).stream().map(row->copy(row,PriceSnapshot::new)).toList();}
 @PutMapping("/prices") public void updatePrice(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@Valid @RequestBody UpdatePriceRequest request){auth(key);service.updatePrice(request);}
}

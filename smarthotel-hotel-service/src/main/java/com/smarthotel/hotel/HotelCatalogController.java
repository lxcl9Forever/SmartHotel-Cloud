package com.smarthotel.hotel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smarthotel.hotel.entity.*;
import com.smarthotel.contract.HotelCatalogContract.*;
import com.smarthotel.hotel.mapper.*;
import com.smarthotel.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.temporal.ChronoUnit;
@RestController @RequestMapping("/internal/v1/hotel-catalog")
public class HotelCatalogController {
 private final RoomTypeMapper types;private final RoomPriceMapper prices;private final HotelInfoMapper hotels;private final RoomTypeImageMapper images;private final byte[] token;
 public HotelCatalogController(RoomTypeMapper types,RoomPriceMapper prices,HotelInfoMapper hotels,RoomTypeImageMapper images,@Value("${smarthotel.internal.token}") String secret){if(secret.length()<32)throw new IllegalArgumentException("Internal token must contain at least 32 characters");this.types=types;this.prices=prices;this.hotels=hotels;this.images=images;token=secret.getBytes(StandardCharsets.UTF_8);}
 private <T>T copy(Object source,java.util.function.Supplier<T> factory){if(source==null)return null;var target=factory.get();org.springframework.beans.BeanUtils.copyProperties(source,target);return target;}
 private void auth(String key){if(!MessageDigest.isEqual(token,key.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(401,"内部酒店身份验证失败");}
 private void ids(List<Long> ids){if(ids==null||ids.size()>100||ids.stream().anyMatch(id->id==null||id<=0))throw new BusinessException(400,"房型编号批次不合法");}
 @PostMapping("/types") public List<TypeSnapshot> types(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@RequestBody TypeQuery q){auth(key);ids(q.ids());return q.ids().isEmpty()?List.of():types.selectList(new LambdaQueryWrapper<RoomType>().in(RoomType::getRoomTypeId,q.ids()).orderByAsc(RoomType::getRoomTypeId)).stream().map(row->copy(row,TypeSnapshot::new)).toList();}
 @GetMapping("/active-types") public List<TypeSnapshot> active(@RequestHeader(value="X-Internal-Token",defaultValue="") String key){auth(key);return types.selectList(new LambdaQueryWrapper<RoomType>().eq(RoomType::getStatus,1).orderByAsc(RoomType::getSortNo)).stream().map(row->copy(row,TypeSnapshot::new)).toList();}
 @PostMapping("/prices") @Transactional(readOnly=true) public List<PriceSnapshot> prices(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@RequestBody PriceQuery q){auth(key);ids(q.ids());if(q.from()==null||q.to()==null||!q.from().isBefore(q.to())||ChronoUnit.DAYS.between(q.from(),q.to())>366)throw new BusinessException(400,"房价日期范围不合法");return q.ids().isEmpty()?List.of():prices.selectList(new LambdaQueryWrapper<RoomPrice>().in(RoomPrice::getRoomTypeId,q.ids()).ge(RoomPrice::getPriceDate,q.from()).lt(RoomPrice::getPriceDate,q.to()).orderByAsc(RoomPrice::getRoomTypeId).orderByAsc(RoomPrice::getPriceDate)).stream().map(row->copy(row,PriceSnapshot::new)).toList();}
 @GetMapping("/hotel") public HotelReply hotel(@RequestHeader(value="X-Internal-Token",defaultValue="") String key){auth(key);return new HotelReply(copy(hotels.selectOne(new LambdaQueryWrapper<HotelInfo>().eq(HotelInfo::getStatus,1).orderByAsc(HotelInfo::getHotelId).last("LIMIT 1")),HotelSnapshot::new));}
 @GetMapping("/images/{id}") public List<ImageSnapshot> images(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@PathVariable Long id){auth(key);if(id<=0)throw new BusinessException(400,"房型编号不合法");return images.selectList(new LambdaQueryWrapper<RoomTypeImage>().eq(RoomTypeImage::getRoomTypeId,id).orderByDesc(RoomTypeImage::getIsCover).orderByAsc(RoomTypeImage::getSortNo)).stream().map(row->copy(row,ImageSnapshot::new)).toList();}
}

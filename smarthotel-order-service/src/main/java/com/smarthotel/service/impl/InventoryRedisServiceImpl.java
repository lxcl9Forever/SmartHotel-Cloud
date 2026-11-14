package com.smarthotel.service.impl;
import com.smarthotel.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.LocalDate;
@Service @RequiredArgsConstructor
public class InventoryRedisServiceImpl implements InventoryRedisService {
 private final StringRedisTemplate redis;
 private final InventoryProjection projection;
 public Integer getAvailableQty(Long type,LocalDate day) {String value=redis.opsForValue().get("hotel:inventory:"+type+":"+day);return value==null?null:Integer.valueOf(value);}
 public void initInventory() {projection.rebuildAll();}
 public void syncInventory(Long type,LocalDate day,Integer ignored) {projection.enqueue(type,day,day.plusDays(1));}
}

package com.smarthotel.inventory;
import com.smarthotel.contract.InventoryTccContract.*;
import com.smarthotel.inventory.service.*;
import com.smarthotel.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.temporal.ChronoUnit;
@RestController @RequestMapping("/internal/v1/inventory")
public class InventoryController {
 private final InventoryOwner owner;private final InventoryProjection projection;private final byte[] token;
 public InventoryController(InventoryOwner owner,InventoryProjection projection,@Value("${smarthotel.internal.token}") String secret){if(secret.length()<32)throw new IllegalArgumentException("Internal token must contain at least 32 characters");this.owner=owner;this.projection=projection;token=secret.getBytes(StandardCharsets.UTF_8);}
 private void auth(String value){if(!MessageDigest.isEqual(token,value.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(401,"内部库存身份验证失败");}
 private void range(Range r){if(r==null||r.roomTypeId()==null||r.from()==null||r.to()==null||!r.from().isBefore(r.to())||ChronoUnit.DAYS.between(r.from(),r.to())>366)throw new BusinessException(400,"无效的库存日期范围");}
 @PostMapping("/prepare") public Reply prepare(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@RequestBody Command c){auth(key);return owner.prepare(c);}
 @PostMapping("/decide") public Reply decide(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@RequestBody Decision d){auth(key);return owner.decide(d);}
 @PostMapping("/set-total") public void total(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@RequestBody SetTotal c){auth(key);owner.setTotal(c);}
 @PostMapping("/enqueue") public void enqueue(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@RequestBody Range r){auth(key);range(r);projection.enqueue(r.roomTypeId(),r.from(),r.to());}
 @PostMapping("/synchronize") public boolean sync(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@RequestBody Range r){auth(key);range(r);return projection.synchronizeRange(r.roomTypeId(),r.from(),r.to());}
 @PostMapping("/rebuild") public void rebuild(@RequestHeader(value="X-Internal-Token",defaultValue="") String key){auth(key);projection.rebuildAll();}
}

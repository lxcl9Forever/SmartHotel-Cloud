package com.smarthotel.inventory;
import com.smarthotel.contract.InventoryReadContract.Query;
import com.smarthotel.contract.InventoryReadContract.Snapshot;
import com.smarthotel.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;import java.security.MessageDigest;import java.nio.charset.StandardCharsets;import java.time.temporal.ChronoUnit;
@RestController @RequestMapping("/internal/v1/inventory")
public class InventoryReadController {
 private final JdbcTemplate jdbc;private final byte[] token;
 public InventoryReadController(JdbcTemplate jdbc,@Value("${smarthotel.internal.token}") String secret){if(secret.length()<32)throw new IllegalArgumentException("Invalid internal token");this.jdbc=jdbc;token=secret.getBytes(StandardCharsets.UTF_8);}
 @PostMapping("/day") @Transactional(readOnly=true) public List<Snapshot> day(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@RequestBody java.time.LocalDate day){
  if(!MessageDigest.isEqual(token,key.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(401,"内部库存身份验证失败");
  return jdbc.query("SELECT * FROM room_inventory WHERE inventory_date=? ORDER BY room_type_id",BeanPropertyRowMapper.newInstance(Snapshot.class),day);
 }
 @PostMapping("/query") @Transactional(readOnly=true) public List<Snapshot> query(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,@RequestBody Query q){
  if(!MessageDigest.isEqual(token,key.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(401,"内部库存身份验证失败");
  if(q.ids()==null||q.ids().size()>100||q.ids().stream().anyMatch(id->id==null||id<=0)||q.from()==null||q.to()==null||!q.from().isBefore(q.to())||ChronoUnit.DAYS.between(q.from(),q.to())>366)throw new BusinessException(400,"库存查询范围不合法");
  if(q.ids().isEmpty())return List.of();var ids=q.ids().stream().distinct().toList();var params=new ArrayList<Object>(ids);params.add(q.from());params.add(q.to());
  return jdbc.query("SELECT * FROM room_inventory WHERE room_type_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND inventory_date>=? AND inventory_date<? ORDER BY room_type_id,inventory_date",BeanPropertyRowMapper.newInstance(Snapshot.class),params.toArray());
 }
}

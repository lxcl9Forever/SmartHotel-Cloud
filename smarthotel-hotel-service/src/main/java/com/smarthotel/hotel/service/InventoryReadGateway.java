package com.smarthotel.hotel.service;
import com.smarthotel.hotel.client.InventoryReadClient;
import com.smarthotel.contract.InventoryReadContract.Query;import com.smarthotel.hotel.model.RoomInventory;import com.smarthotel.exception.BusinessException;
import org.springframework.stereotype.Service;import org.springframework.beans.factory.annotation.Value;
import java.time.LocalDate;import java.util.*;
@Service public class InventoryReadGateway {
 private final InventoryReadClient client;private final String token;
 public InventoryReadGateway(InventoryReadClient client,@Value("${smarthotel.internal.token}") String token){this.client=client;this.token=token;}
 private RoomInventory entity(com.smarthotel.contract.InventoryReadContract.Snapshot row){
  if(row==null)throw new IllegalStateException("Missing inventory row");
  var entity=new RoomInventory();org.springframework.beans.BeanUtils.copyProperties(row,entity);return entity;
 }
 public List<RoomInventory> query(Long id,LocalDate from,LocalDate to){return queryMany(List.of(id),from,to);}
 public List<RoomInventory> queryMany(Collection<Long> input,LocalDate from,LocalDate to){
  if(from==null||to==null||!from.isBefore(to))throw new BusinessException(400,"库存日期范围不合法");
  var ids=input.stream().filter(Objects::nonNull).filter(id->id>0).distinct().toList();var result=new ArrayList<RoomInventory>();
  try{for(int n=0;n<ids.size();n+=100){var batch=ids.subList(n,Math.min(n+100,ids.size()));for(LocalDate day=from;day.isBefore(to);){LocalDate next=day.plusDays(366).isBefore(to)?day.plusDays(366):to;var rows=client.query(token,new Query(batch,day,next));if(rows==null)throw new IllegalStateException("Missing inventory response");for(var row:rows)result.add(entity(row));day=next;}}}catch(RuntimeException failure){throw new BusinessException(503,"库存查询服务暂不可用，请稍后重试");}
  result.sort(Comparator.comparing(RoomInventory::getRoomTypeId).thenComparing(RoomInventory::getInventoryDate));return result;
 }
}

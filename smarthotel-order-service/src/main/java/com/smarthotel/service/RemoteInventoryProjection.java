package com.smarthotel.service;
import com.smarthotel.client.InventoryClient;
import com.smarthotel.contract.InventoryTccContract.*;
import com.smarthotel.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
@Service @ConditionalOnProperty(name="smarthotel.inventory.mode",havingValue="remote")
public class RemoteInventoryProjection implements InventoryProjection {
 private final InventoryClient client;private final String token;
 public RemoteInventoryProjection(InventoryClient client,@Value("${smarthotel.internal.token}") String token){this.client=client;this.token=token;}
 public void enqueue(Long type,LocalDate from,LocalDate to){try{client.enqueue(token,new Range(type,from,to));}catch(RuntimeException unavailable){throw new BusinessException(503,"库存服务暂不可用");}}
 public boolean synchronizeRange(Long type,LocalDate from,LocalDate to){try{return Boolean.TRUE.equals(client.synchronize(token,new Range(type,from,to)));}catch(RuntimeException failure){return false;}}
 public boolean syncOne(Long type,LocalDate day){return synchronizeRange(type,day,day.plusDays(1));}
 public void retryPending(){} // Only the inventory owner runs the cache projection worker.
 public void rebuildAll(){client.rebuild(token);}
 public void setTotal(Long type,LocalDate date,Integer qty,Integer version){try{client.setTotal(token,new SetTotal(type,date,qty,version));}catch(feign.FeignException e){throw new BusinessException(e.status()==400||e.status()==409?e.status():503,"房量更新未确认，请刷新后重试");}}
}

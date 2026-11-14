package com.smarthotel.service;
import com.smarthotel.client.OrderReadClient;
import com.smarthotel.contract.order.OrderSnapshot;
import com.smarthotel.exception.BusinessException;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service public class OrderDirectory {
 private final OrderReadClient client;private final String token;
 public OrderDirectory(OrderReadClient client,@Value("${smarthotel.internal.token}") String token){this.client=client;this.token=token;}
 public OrderSnapshot find(Long id){try{return client.find(token,id);}catch(FeignException e){throw new BusinessException(e.status()==404?404:503,e.status()==404?"订单不存在":"订单服务暂不可用");}}
 public com.smarthotel.vo.BookingOrderVO view(String username,Long id){try{return client.view(token,id,username);}catch(FeignException e){int status=java.util.Set.of(403,404).contains(e.status())?e.status():503;throw new BusinessException(status,"订单查询暂不可用或无权访问");}}
}

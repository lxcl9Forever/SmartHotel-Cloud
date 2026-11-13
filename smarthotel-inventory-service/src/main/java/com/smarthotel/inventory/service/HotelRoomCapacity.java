package com.smarthotel.inventory.service;
import com.smarthotel.inventory.client.HotelCapacityClient;import com.smarthotel.exception.BusinessException;
import org.springframework.stereotype.Service;import org.springframework.beans.factory.annotation.Value;
@Service public class HotelRoomCapacity implements RoomCapacity {
 private final HotelCapacityClient client;private final String token;
 public HotelRoomCapacity(HotelCapacityClient client,@Value("${smarthotel.internal.token}") String token){this.client=client;this.token=token;}
 public int totalRooms(Long type){try{Integer value=client.capacity(token,type);if(value==null||value<0)throw new IllegalStateException("Missing capacity");return value;}catch(feign.FeignException e){throw new BusinessException(e.status()==404?404:503,"酒店容量资料暂不可用或房型不存在");}catch(RuntimeException e){throw new BusinessException(503,"酒店容量资料暂不可用");}}
}

package com.smarthotel.inventory.service;
import com.smarthotel.inventory.client.HotelCapacityClient;import com.smarthotel.exception.BusinessException;
import feign.*;import org.junit.jupiter.api.Test;import java.util.*;
import static org.mockito.Mockito.*;import static org.assertj.core.api.Assertions.*;
class HotelRoomCapacityTest {
 HotelCapacityClient client=mock(HotelCapacityClient.class);HotelRoomCapacity capacity=new HotelRoomCapacity(client,"synthetic-token");
 FeignException failure(int status){return FeignException.errorStatus("capacity",Response.builder().status(status).reason("test").headers(Map.of()).request(Request.create(Request.HttpMethod.GET,"http://hotel",Map.of(),new byte[0],null,null)).build());}
 @Test void readsCapacityWithoutSharingHotelEntity(){when(client.capacity("synthetic-token",1L)).thenReturn(70);assertThat(capacity.totalRooms(1L)).isEqualTo(70);}
 @Test void unavailableOrEmptyCapacityCannotBeUsed(){when(client.capacity("synthetic-token",1L)).thenThrow(failure(503));assertThatThrownBy(()->capacity.totalRooms(1L)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(503));when(client.capacity("synthetic-token",2L)).thenReturn(null);assertThatThrownBy(()->capacity.totalRooms(2L)).isInstanceOf(BusinessException.class);}
 @Test void missingRoomTypePreservesNotFound(){when(client.capacity("synthetic-token",1L)).thenThrow(failure(404));assertThatThrownBy(()->capacity.totalRooms(1L)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(404));}
}

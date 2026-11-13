package com.smarthotel.hotel;
import com.smarthotel.hotel.mapper.RoomTypeMapper;import com.smarthotel.hotel.entity.RoomType;import com.smarthotel.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;import org.springframework.context.annotation.Import;import org.springframework.test.context.bean.override.mockito.MockitoBean;import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;import static org.mockito.Mockito.*;
@WebMvcTest(controllers=HotelCapacityController.class,properties="smarthotel.internal.token=capacity-test-token-at-least-32-characters") @Import({ReadOnlySecurity.class,GlobalExceptionHandler.class})
class HotelCapacityHttpTest {
 @Autowired MockMvc http;@MockitoBean RoomTypeMapper types;
 @Test void capacityRequiresInternalIdentity()throws Exception{http.perform(get("/internal/v1/hotel/capacity/1")).andExpect(status().isUnauthorized());verifyNoInteractions(types);}
 @Test void missingTypeAndNarrowCapacityReply()throws Exception{http.perform(get("/internal/v1/hotel/capacity/999").header("X-Internal-Token","capacity-test-token-at-least-32-characters")).andExpect(status().isNotFound());var type=new RoomType();type.setRoomTypeId(1L);type.setTotalRooms(70);when(types.selectById(1L)).thenReturn(type);http.perform(get("/internal/v1/hotel/capacity/1").header("X-Internal-Token","capacity-test-token-at-least-32-characters")).andExpect(status().isOk()).andExpect(content().string("70"));}
}

package com.smarthotel.hotel;
import com.smarthotel.hotel.service.HotelAdminService;
import com.smarthotel.exception.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(controllers=HotelAdminController.class,properties="smarthotel.internal.token=hotel-test-token-at-least-32-characters")
@Import({ReadOnlySecurity.class,GlobalExceptionHandler.class})
class HotelAdminHttpTest {
 @Autowired MockMvc http;@MockitoBean HotelAdminService service;
 String token="hotel-test-token-at-least-32-characters";
 String body="{\"roomTypeId\":1,\"priceDate\":\"2030-01-01\",\"salePrice\":688}";
 @Test void unauthenticatedWriteNeverReachesStore()throws Exception {http.perform(put("/internal/v1/hotel-admin/prices").contentType("application/json").content(body)).andExpect(status().isUnauthorized());verifyNoInteractions(service);}
 @Test void authenticatedWriteDelegatesValidatedBody()throws Exception {http.perform(put("/internal/v1/hotel-admin/prices").header("X-Internal-Token",token).contentType("application/json").content(body)).andExpect(status().isOk());verify(service).updatePrice(argThat(r->r.getSalePrice().intValue()==688));}
 @Test void invalidPriceCannotReachStore()throws Exception {http.perform(put("/internal/v1/hotel-admin/prices").header("X-Internal-Token",token).contentType("application/json").content(body.replace("688","0"))).andExpect(status().isBadRequest());verifyNoInteractions(service);}
 @Test void missingDatePreservesNotFoundStatus()throws Exception {doThrow(new BusinessException(404,"该日期没有房价数据")).when(service).updatePrice(any());http.perform(put("/internal/v1/hotel-admin/prices").header("X-Internal-Token",token).contentType("application/json").content(body)).andExpect(status().isNotFound());}
 @Test void managementReadRequiresIdentityAndPublicWriteIsDenied()throws Exception {http.perform(get("/internal/v1/hotel-admin/room-types")).andExpect(status().isUnauthorized());http.perform(put("/api/v1/admin/prices").contentType("application/json").content(body)).andExpect(status().isForbidden());verifyNoInteractions(service);}

 @Test void authenticatedManagementReadPreservesWireFields()throws Exception {var row=new com.smarthotel.hotel.entity.RoomType();row.setRoomTypeId(7L);row.setTypeName("Retired");row.setStatus(0);when(service.listRoomTypes()).thenReturn(java.util.List.of(row));http.perform(get("/internal/v1/hotel-admin/room-types").header("X-Internal-Token",token)).andExpect(status().isOk()).andExpect(jsonPath("$[0].roomTypeId").value(7)).andExpect(jsonPath("$[0].typeName").value("Retired")).andExpect(jsonPath("$[0].status").value(0));}
}

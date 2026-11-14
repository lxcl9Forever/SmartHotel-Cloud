package com.smarthotel.service;
import com.smarthotel.client.HotelAdminClient;
import com.smarthotel.mapper.*;
import com.smarthotel.service.impl.AdminHotelServiceImpl;
import com.smarthotel.dto.admin.UpdatePriceRequest;
import com.smarthotel.entity.Room;
import com.smarthotel.service.HotelCatalog;
import com.smarthotel.exception.BusinessException;
import feign.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.time.LocalDate;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class HotelAdminProxyTest {
  RoomMapper rooms=mock(RoomMapper.class);
  InventoryReadGateway stock=mock(InventoryReadGateway.class);
  HotelAdminClient remote=mock(HotelAdminClient.class);
  HotelCatalog catalog=mock(HotelCatalog.class);
  InventoryProjection projection=mock(InventoryProjection.class);
  AdminHotelServiceImpl service=new AdminHotelServiceImpl(remote,rooms,stock,catalog,projection);
  HotelAdminProxyTest() {
    ReflectionTestUtils.setField(service,"token","synthetic-hotel-proxy-token");
  }
  UpdatePriceRequest req() {
    var r=new UpdatePriceRequest();
    r.setRoomTypeId(1L);
    r.setPriceDate(LocalDate.of(2030,1,1));
    r.setSalePrice(new BigDecimal("688"));
    return r;
  }
  FeignException failure(int status) {
    return FeignException.errorStatus("hotel",Response.builder().status(status).reason("synthetic").headers(Map.of()).request(Request.create(Request.HttpMethod.PUT,"http://hotel",Map.of(),new byte[0],null,null)).build());
  }
  @Test void physicalRoomQueryStaysWithRoomStateOwner() {
    var values=List.of(new Room());
    when(rooms.selectList(any())).thenReturn(values);
    assertThat(service.listRooms(1L,"VACANT")).isSameAs(values);
    verifyNoInteractions(remote,stock);
  }
  @Test void priceWritesOnlyInvokeOwner() {
    var r=req();
    service.updatePrice(r);
    verify(remote).updatePrice("synthetic-hotel-proxy-token",r);
    verifyNoInteractions(catalog,projection);
  }
  @Test void ownerFailureDoesNotFallBackToLocalQueries() {
    when(remote.listRoomTypes(anyString())).thenThrow(failure(503));
    assertThatThrownBy(service::listRoomTypes).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(503));
    verifyNoInteractions(catalog,projection);
  }
  @Test void missingPriceKeeps404() {
    doThrow(failure(404)).when(remote).updatePrice(anyString(),any());
    assertThatThrownBy(()->service.updatePrice(req())).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(404));
  }
  @Test void typeSnapshotsPreserveInactiveMetadata() {
    var row=new com.smarthotel.contract.HotelCatalogContract.TypeSnapshot();
    row.setRoomTypeId(7L);
    row.setTypeName("停售房型");
    row.setStatus(0);
    row.setTotalRooms(12);
    when(remote.listRoomTypes(anyString())).thenReturn(List.of(row));
    var actual=service.listRoomTypes().get(0);
    assertThat(actual.getRoomTypeId()).isEqualTo(7L);
    assertThat(actual.getTypeName()).isEqualTo(row.getTypeName());
    assertThat(actual.getStatus()).isZero();
    assertThat(actual.getTotalRooms()).isEqualTo(12);
  }
  @Test void priceSnapshotsPreserveDateAndDecimal() {
    var day=LocalDate.of(2030,1,1);
    var row=new com.smarthotel.contract.HotelCatalogContract.PriceSnapshot();
    row.setRoomTypeId(1L);
    row.setPriceDate(day);
    row.setSalePrice(new BigDecimal("688.50"));
    when(remote.listPrices(anyString(),eq(1L),eq(day),eq(day))).thenReturn(List.of(row));
    var actual=service.listPrices(1L,day,day).get(0);
    assertThat(actual.getPriceDate()).isEqualTo(day);
    assertThat(actual.getSalePrice()).isEqualByComparingTo("688.50");
  }
  @Test void missingManagementBodiesAreNotSuccessfulEmptyLists() {
    when(remote.listRoomTypes(anyString())).thenReturn(null);
    when(remote.listPrices(anyString(),any(),any(),any())).thenReturn(null);
    assertThatThrownBy(service::listRoomTypes).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(503));
    assertThatThrownBy(()->service.listPrices(1L,LocalDate.now(),LocalDate.now())).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(503));
  }
}

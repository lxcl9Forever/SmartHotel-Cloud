package com.smarthotel.service.impl;
import com.smarthotel.service.HotelCatalog;
import com.smarthotel.service.InventoryReadGateway;
import com.smarthotel.entity.*;
import com.smarthotel.model.hotel.RoomType;
import com.smarthotel.model.hotel.RoomPrice;
import com.smarthotel.model.inventory.RoomInventory;
import com.smarthotel.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class RoomTypeInventoryBoundaryTest {
 final HotelCatalog catalog=mock(HotelCatalog.class);
 final InventoryReadGateway stock=mock(InventoryReadGateway.class);
 final RoomTypeServiceImpl service=new RoomTypeServiceImpl(catalog,stock);
 final LocalDate day=LocalDate.now().plusDays(2);
 RoomType type(){var t=new RoomType();t.setRoomTypeId(1L);t.setMaxGuests(2);return t;}
 void prices(){var p=new RoomPrice();p.setRoomTypeId(1L);p.setPriceDate(day);p.setSalePrice(new BigDecimal("688"));when(catalog.activeTypes()).thenReturn(List.of(type()));when(catalog.priceIndex(List.of(1L),day,day.plusDays(1))).thenReturn(Map.of(1L,List.of(p)));}
 @Test void compatibilitySearchUsesAuthoritativeStockAndPreservesPrice(){
  prices();var i=new RoomInventory();i.setRoomTypeId(1L);i.setInventoryDate(day);i.setTotalQty(10);i.setLockedQty(2);i.setSoldQty(3);
  when(stock.queryMany(List.of(1L),day,day.plusDays(1))).thenReturn(List.of(i));
  var rows=service.searchAvailableRoomTypes(day,day.plusDays(1),2,2);
  assertThat(rows).hasSize(1);assertThat(rows.get(0).getAvailableQty()).isEqualTo(5);assertThat(rows.get(0).getPricePerRoom()).isEqualByComparingTo("688");assertThat(rows.get(0).getTotalPrice()).isEqualByComparingTo("1376");
  verify(stock).queryMany(List.of(1L),day,day.plusDays(1));
 }
 @Test void missingDateCannotBecomeSellable(){prices();when(stock.queryMany(List.of(1L),day,day.plusDays(1))).thenReturn(List.of());assertThat(service.searchAvailableRoomTypes(day,day.plusDays(1),1,1)).isEmpty();}
 @Test void inventoryOutageCannotBypassTheOwner(){prices();when(stock.queryMany(List.of(1L),day,day.plusDays(1))).thenThrow(new BusinessException(503,"unavailable"));assertThatThrownBy(()->service.searchAvailableRoomTypes(day,day.plusDays(1),1,1)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(503));}
}

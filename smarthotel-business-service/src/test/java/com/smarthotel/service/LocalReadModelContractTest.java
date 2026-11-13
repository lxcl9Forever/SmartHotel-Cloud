package com.smarthotel.service;
import java.beans.Introspector;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import com.smarthotel.contract.HotelCatalogContract;
import com.smarthotel.contract.InventoryReadContract;
import static org.assertj.core.api.Assertions.*;
/** Detect DTO drift before BeanUtils silently drops prices or stock fields. */
class LocalReadModelContractTest {
 @Test void snapshotsPreserveEveryPropertyWithoutPersistenceMetadata() throws Exception {
  Class<?>[][] pairs={{HotelCatalogContract.HotelSnapshot.class,com.smarthotel.model.hotel.HotelInfo.class},{HotelCatalogContract.TypeSnapshot.class,com.smarthotel.model.hotel.RoomType.class},{HotelCatalogContract.ImageSnapshot.class,com.smarthotel.model.hotel.RoomTypeImage.class},{HotelCatalogContract.PriceSnapshot.class,com.smarthotel.model.hotel.RoomPrice.class},{InventoryReadContract.Snapshot.class,com.smarthotel.model.inventory.RoomInventory.class}};
  for(var pair:pairs){
   Class<?> source=pair[0],target=pair[1];
   assertThat(Arrays.stream(target.getAnnotations()).map(a->a.annotationType().getName())).noneMatch(n->n.startsWith("com.baomidou."));
   for(var field:target.getDeclaredFields())assertThat(Arrays.stream(field.getAnnotations()).map(a->a.annotationType().getName())).noneMatch(n->n.startsWith("com.baomidou."));
   var input=source.getDeclaredConstructor().newInstance();var output=target.getDeclaredConstructor().newInstance();
   var properties=Introspector.getBeanInfo(source,Object.class).getPropertyDescriptors();
   var actual=Introspector.getBeanInfo(target,Object.class).getPropertyDescriptors();
   assertThat(Arrays.stream(actual).map(p->p.getName()).toList()).containsExactlyInAnyOrderElementsOf(Arrays.stream(properties).map(p->p.getName()).toList());
   for(var p:properties)p.getWriteMethod().invoke(input,sample(p.getPropertyType()));
   BeanUtils.copyProperties(input,output);
   for(var p:properties){var dest=Arrays.stream(actual).filter(x->x.getName().equals(p.getName())).findFirst().orElseThrow();assertThat(dest.getReadMethod().invoke(output)).as(target.getName()+"."+p.getName()).isEqualTo(p.getReadMethod().invoke(input));}
  }
 }
 private Object sample(Class<?> type){
  if(type==Long.class)return 71L;if(type==Integer.class)return 23;if(type==String.class)return "field-value";
  if(type==BigDecimal.class)return new BigDecimal("688.50");if(type==LocalDate.class)return LocalDate.of(2026,11,12);
  if(type==LocalDateTime.class)return LocalDateTime.of(2026,11,12,15,31,29);if(type==LocalTime.class)return LocalTime.of(14,30);
  throw new AssertionError("Add a sample for "+type);
 }
}

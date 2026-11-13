package com.smarthotel.contract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarthotel.contract.HotelCatalogContract.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class HotelWireContractTest {
 private void fields(Object row,String... expected)throws Exception {var mapper=new ObjectMapper();var tree=mapper.readTree(mapper.writeValueAsString(row));var actual=new HashSet<String>();tree.fieldNames().forEachRemaining(actual::add);assertThat(actual).containsExactlyInAnyOrder(expected);assertThat(mapper.readValue(tree.toString(),row.getClass())).isEqualTo(row);}
 @Test void hotelSnapshotJsonIsStable()throws Exception{fields(new HotelSnapshot(),"hotelId","hotelName","address","phone","description","checkInTime","checkOutTime","status","createTime","updateTime");}
 @Test void typeSnapshotJsonIsStable()throws Exception{fields(new TypeSnapshot(),"roomTypeId","typeCode","typeName","area","bedType","bedCount","maxGuests","totalRooms","basePrice","description","status","sortNo","createTime","updateTime");}
 @Test void imageSnapshotJsonIsStable()throws Exception{fields(new ImageSnapshot(),"imageId","roomTypeId","imageUrl","isCover","sortNo","createTime");}
 @Test void priceSnapshotJsonIsStable()throws Exception{fields(new PriceSnapshot(),"priceId","roomTypeId","priceDate","salePrice","createTime","updateTime");}
 @Test void wireDtosHaveNoPersistenceAnnotationsOrEntityFields(){for(Class<?> type:List.of(HotelSnapshot.class,TypeSnapshot.class,ImageSnapshot.class,PriceSnapshot.class)){assertThat(type.getAnnotations()).isEmpty();for(var field:type.getDeclaredFields())assertThat(field.getType().getName()).doesNotStartWith("com.smarthotel.entity.");}}
}

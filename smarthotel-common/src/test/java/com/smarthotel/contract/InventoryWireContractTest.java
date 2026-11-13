package com.smarthotel.contract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarthotel.contract.InventoryReadContract.Snapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class InventoryWireContractTest {
 @Test void versionOneFieldsRemainStable()throws Exception{
  var mapper=new ObjectMapper();var row=new Snapshot();row.setInventoryId(7L);row.setRoomTypeId(2L);row.setTotalQty(10);row.setLockedQty(2);row.setSoldQty(3);row.setVersion(4);
  var tree=mapper.readTree(mapper.writeValueAsString(row));var fields=new HashSet<String>();tree.fieldNames().forEachRemaining(fields::add);
  assertThat(fields).containsExactlyInAnyOrder("inventoryId","roomTypeId","inventoryDate","totalQty","lockedQty","soldQty","version","createTime","updateTime");
  assertThat(mapper.readValue(tree.toString(),Snapshot.class)).isEqualTo(row);
 }
 @Test void wireTypeCannotCarryPersistenceOrEntityDependencies(){
  assertThat(Snapshot.class.getAnnotations()).isEmpty();
  for(var field:Snapshot.class.getDeclaredFields())assertThat(field.getType().getName()).doesNotStartWith("com.smarthotel.entity.");
 }
}

package com.smarthotel.service;
import com.smarthotel.client.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ServiceTransportContractTest {
 private void inspect(Type type,Set<Type> seen){
  if(!seen.add(type))return;
  assertThat(type.getTypeName()).doesNotContain("com.smarthotel.entity.", ".entity.", ".audit.", "com.smarthotel.model.");
  if(type instanceof ParameterizedType p){inspect(p.getRawType(),seen);for(var t:p.getActualTypeArguments())inspect(t,seen);}
  if(type instanceof Class<?> c && c.getName().startsWith("com.smarthotel."))for(var field:c.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers()))inspect(field.getGenericType(),seen);
 }
 @Test void allBusinessFeignPayloadsAvoidPersistenceEntities(){
  for(var client:List.of(IdentityClient.class,UserDirectoryClient.class,HotelCatalogClient.class,HotelAdminClient.class,InventoryClient.class))for(var method:client.getDeclaredMethods()){
   var seen=new HashSet<Type>();inspect(method.getGenericReturnType(),seen);for(var p:method.getGenericParameterTypes())inspect(p,seen);
  }
 }
}

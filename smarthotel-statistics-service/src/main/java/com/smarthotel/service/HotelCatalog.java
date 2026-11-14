package com.smarthotel.service;
import com.smarthotel.client.HotelCatalogClient;
import com.smarthotel.contract.HotelCatalogContract.*;
import com.smarthotel.entity.*;
import com.smarthotel.model.hotel.HotelInfo;
import com.smarthotel.model.hotel.RoomType;
import com.smarthotel.model.hotel.RoomTypeImage;
import com.smarthotel.model.hotel.RoomPrice;
import com.smarthotel.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.time.LocalDate;
import java.util.function.Supplier;
/** Read-through boundary: no SQL fallback and no stale cross-request cache. */
@Service public class HotelCatalog {
  private final HotelCatalogClient client;
  private final String token;
  public HotelCatalog(HotelCatalogClient client,@Value("${smarthotel.internal.token}") String token) {
    this.client=client;
    this.token=token;
  }
  private <T>T model(Object source,Supplier<T> factory) {
    if(source==null)return null;
    var target=factory.get();
    org.springframework.beans.BeanUtils.copyProperties(source,target);
    return target;
  }
  private <T>T remote(Supplier<T> call) {
    try {
      T result=call.get();
      if(result==null)throw new IllegalStateException("Empty catalog response");
      return result;
    }
    catch(RuntimeException error) {
      throw new BusinessException(503,"酒店资料服务暂不可用，请稍后重试");
    }
  }
  public Map<Long,RoomType> types(Collection<Long> input) {
    var ids=input.stream().filter(Objects::nonNull).filter(id->id>0).distinct().toList();
    var result=new LinkedHashMap<Long,RoomType>();
    for(int i=0;i<ids.size();i+=100) {
      var batch=ids.subList(i,Math.min(i+100,ids.size()));
      for(var room:remote(()->client.types(token,new TypeQuery(batch))))result.put(room.getRoomTypeId(),model(room,RoomType::new));
    }
    return result;
  }
  public RoomType find(Long id) {
    return id==null?null:types(List.of(id)).get(id);
  }
  public List<RoomType> activeTypes() {
    return remote(()->client.active(token)).stream().map(row->model(row,RoomType::new)).toList();
  }
  public Map<Long,List<RoomPrice>> priceIndex(Collection<Long> input,LocalDate from,LocalDate to) {
    var ids=input.stream().filter(Objects::nonNull).filter(id->id>0).distinct().toList();
    var result=new HashMap<Long,List<RoomPrice>>();
    if(from==null||to==null||!from.isBefore(to))return result;
    for(int i=0;i<ids.size();i+=100) {
      var batch=ids.subList(i,Math.min(i+100,ids.size()));
      for(LocalDate day=from;day.isBefore(to);) {
        LocalDate next=day.plusDays(366).isBefore(to)?day.plusDays(366):to;
        var q=new PriceQuery(batch,day,next);
        for(var price:remote(()->client.prices(token,q)))result.computeIfAbsent(price.getRoomTypeId(),unused->new ArrayList<>()).add(model(price,RoomPrice::new));
        day=next;
      }
    }
    result.values().forEach(list->list.sort(Comparator.comparing(RoomPrice::getPriceDate)));
    return result;
  }
  public List<RoomPrice> prices(Long type,LocalDate from,LocalDate to) {
    return priceIndex(List.of(type),from,to).getOrDefault(type,List.of());
  }
  public HotelInfo hotel() {
    return model(remote(()->client.hotel(token)).hotel(),HotelInfo::new);
  }
  public List<RoomTypeImage> images(Long id) {
    return remote(()->client.images(token,id)).stream().map(row->model(row,RoomTypeImage::new)).toList();
  }
}

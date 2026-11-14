package com.smarthotel.service;
import com.smarthotel.integration.RowStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;
@Service public class OrderProjectionRefresh {
  private final OrderDirectory orders;
  private final RowStore rows;
  private final ObjectMapper json;
  public OrderProjectionRefresh(OrderDirectory orders,RowStore rows,ObjectMapper json) {
    this.orders=orders;
    this.rows=rows;
    this.json=json;
  }
  public void refresh(Long id) {
    var snapshot=orders.find(id);
    var values=json.convertValue(snapshot,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {
    }
    );
    var mapped=new LinkedHashMap<String,Object>();
    values.forEach((k,v)->mapped.put(k.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT),v));
    rows.upsert("order_snapshot","order_id",mapped);
  }
}

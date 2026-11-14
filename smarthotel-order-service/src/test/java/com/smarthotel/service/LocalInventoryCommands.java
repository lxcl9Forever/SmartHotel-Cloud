// Historical test fixture only; excluded from the deployable application.
package com.smarthotel.service;

import com.smarthotel.entity.BookingOrder;
import com.smarthotel.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Local transaction boundary first; these commands are not yet remote calls. */
 @RequiredArgsConstructor
public class LocalInventoryCommands implements InventoryCommands {
    private final JdbcTemplate jdbc;
    private final InventoryProjection projection;

    @Transactional(propagation=Propagation.MANDATORY)
    public void reserve(BookingOrder order) { apply(order,"RESERVE",order.getCheckInDate(),order.getCheckOutDate(),1,0,"WAIT_PAY"); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void confirm(BookingOrder order) { apply(order,"CONFIRM",order.getCheckInDate(),order.getCheckOutDate(),-1,1,"WAIT_PAY"); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancel(BookingOrder order) { apply(order,"RELEASE_LOCKED",order.getCheckInDate(),order.getCheckOutDate(),-1,0,"WAIT_PAY"); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void refund(BookingOrder order) { apply(order,"RELEASE_SOLD",order.getCheckInDate(),order.getCheckOutDate(),0,-1,"CONFIRMED"); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void extend(BookingOrder order, LocalDate until) { apply(order,"EXTEND:"+until,order.getCheckOutDate(),until,0,1,"CHECKED_IN"); }

    private void apply(BookingOrder order,String kind,LocalDate from,LocalDate to,int locked,int sold,String requiredState) {
        if(order.getOrderId()==null||from==null||to==null||!from.isBefore(to)||order.getRoomCount()==null||order.getRoomCount()<=0)
            throw new BusinessException(400,"无效的库存操作参数");
        // Consistent ordering: order -> operation -> inventory dates -> projection queue.
        var owner=jdbc.queryForMap("SELECT order_status,room_type_id,room_count FROM booking_order WHERE order_id=? FOR UPDATE",order.getOrderId());
        String key=kind+":"+order.getOrderId();
        String signature=order.getRoomTypeId()+"|"+from+"|"+to+"|"+order.getRoomCount();
        jdbc.update("INSERT INTO inventory_operation(operation_key,order_id,operation_type,request_signature,state) VALUES(?,?,?,?,'PENDING') ON DUPLICATE KEY UPDATE operation_key=operation_key",key,order.getOrderId(),kind,signature);
        var operation=jdbc.queryForMap("SELECT request_signature,state FROM inventory_operation WHERE operation_key=? FOR UPDATE",key);
        if(!signature.equals(operation.get("request_signature")))throw new BusinessException(409,"重复库存请求的参数不一致");
        if("DONE".equals(operation.get("state")))return;
        if(!requiredState.equals(owner.get("order_status"))
                ||((Number)owner.get("room_type_id")).longValue()!=order.getRoomTypeId()
                ||((Number)owner.get("room_count")).intValue()!=order.getRoomCount())throw new BusinessException(409,"订单状态与库存操作不匹配");
        var days=jdbc.queryForList("SELECT inventory_id,inventory_date,total_qty,locked_qty,sold_qty FROM room_inventory WHERE room_type_id=? AND inventory_date>=? AND inventory_date<? ORDER BY inventory_date FOR UPDATE",order.getRoomTypeId(),from,to);
        if(days.size()!=ChronoUnit.DAYS.between(from,to))throw new BusinessException(409,"库存日期不完整");
        int dl=locked*order.getRoomCount(),ds=sold*order.getRoomCount();
        for(var day:days) {
            int nextLocked=((Number)day.get("locked_qty")).intValue()+dl,nextSold=((Number)day.get("sold_qty")).intValue()+ds;
            if(nextLocked<0||nextSold<0||nextLocked+nextSold>((Number)day.get("total_qty")).intValue())throw new BusinessException(409,"库存不足或占用数量异常");
        }
        for(var day:days) {
            int updated=jdbc.update("UPDATE room_inventory SET locked_qty=locked_qty+?,sold_qty=sold_qty+?,update_time=CURRENT_TIMESTAMP WHERE inventory_id=?",dl,ds,day.get("inventory_id"));
            if(updated!=1)throw new IllegalStateException("Inventory row disappeared");
        }
        projection.enqueue(order.getRoomTypeId(),from,to);
        jdbc.update("UPDATE inventory_operation SET state='DONE',completed_at=CURRENT_TIMESTAMP WHERE operation_key=?",key);
    }
}

package com.smarthotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smarthotel.entity.Room;
import com.smarthotel.model.hotel.RoomType;
import com.smarthotel.mapper.RoomMapper;
import com.smarthotel.service.AdminHotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import com.smarthotel.model.inventory.RoomInventory;
import com.smarthotel.model.hotel.RoomPrice;
import com.smarthotel.exception.BusinessException;

import java.time.LocalDate;
import com.smarthotel.dto.admin.UpdateInventoryRequest;
import com.smarthotel.dto.admin.UpdatePriceRequest;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminHotelServiceImpl
        implements AdminHotelService {

    private final com.smarthotel.client.HotelAdminClient hotel;
    @org.springframework.beans.factory.annotation.Value("${smarthotel.internal.token}") private String token;



    private final RoomMapper roomMapper;
    private final com.smarthotel.service.InventoryReadGateway inventoryRead;
    private final com.smarthotel.service.HotelCatalog catalog;



    private final com.smarthotel.service.InventoryProjection inventoryProjection;

    private <T> List<T> models(List<?> rows,java.util.function.Supplier<T> factory) {
        if(rows==null)throw new BusinessException(503,"酒店管理服务返回不完整，请稍后重试");
        return rows.stream().map(row->{if(row==null)throw new BusinessException(503,"酒店管理服务返回不完整，请稍后重试");var value=factory.get();org.springframework.beans.BeanUtils.copyProperties(row,value);return value;}).toList();
    }
    /**
     * 管理员查看全部房型
     *
     * 包括已停售房型
     */
    @Override
    public List<RoomType> listRoomTypes() {
        try { return models(hotel.listRoomTypes(token),RoomType::new); } catch(feign.FeignException e) {
            int remoteStatus=e.status(); throw new BusinessException(remoteStatus==400||remoteStatus==404||remoteStatus==409?remoteStatus:503,remoteStatus==404?"该日期没有房价数据":"酒店服务暂不可用或请求被拒绝，请刷新后重试");
        }
    }


    /**
     * 管理员查看实体客房
     *
     * 可按房型和房态筛选
     */
    @Override
    public List<Room> listRooms(
            Long roomTypeId,
            String status) {
        var q=new LambdaQueryWrapper<Room>();
        if(roomTypeId!=null)q.eq(Room::getRoomTypeId,roomTypeId);
        if(status!=null&&!status.isBlank())q.eq(Room::getStatus,status);
        return roomMapper.selectList(q.orderByAsc(Room::getFloorNo).orderByAsc(Room::getRoomNo));
    }

    @Override
    public List<RoomInventory> listInventories(
            Long roomTypeId,
            LocalDate startDate,
            LocalDate endDate) {
        if(roomTypeId==null||startDate==null||endDate==null||startDate.isAfter(endDate))throw new BusinessException(400,"房型和日期范围不合法");
        return inventoryRead.query(roomTypeId,startDate,endDate.plusDays(1));
    }


    @Override
    public List<RoomPrice> listPrices(
            Long roomTypeId,
            LocalDate startDate,
            LocalDate endDate) {
        try { return models(hotel.listPrices(token,roomTypeId,startDate,endDate),RoomPrice::new); } catch(feign.FeignException e) {
            int remoteStatus=e.status(); throw new BusinessException(remoteStatus==400||remoteStatus==404||remoteStatus==409?remoteStatus:503,remoteStatus==404?"该日期没有房价数据":"酒店服务暂不可用或请求被拒绝，请刷新后重试");
        }
    }

    @Override
    @Transactional
    public void updateInventory(
            UpdateInventoryRequest request) {

        if (!(inventoryProjection instanceof com.smarthotel.service.RemoteInventoryProjection remote))
            throw new BusinessException(503,"独立库存模式尚未启用");
        var rows=inventoryRead.query(request.getRoomTypeId(),request.getInventoryDate(),request.getInventoryDate().plusDays(1));
        if(rows.isEmpty())throw new BusinessException(404,"该日期没有房量数据");
        remote.setTotal(request.getRoomTypeId(),request.getInventoryDate(),request.getTotalQty(),rows.get(0).getVersion());
    }

    @Override
    @Transactional
    public void updatePrice(
            UpdatePriceRequest request) {
        try { hotel.updatePrice(token,request); } catch(feign.FeignException e) {
            int remoteStatus=e.status(); throw new BusinessException(remoteStatus==400||remoteStatus==404||remoteStatus==409?remoteStatus:503,remoteStatus==404?"该日期没有房价数据":"酒店服务暂不可用或请求被拒绝，请刷新后重试");
        }
    }

}
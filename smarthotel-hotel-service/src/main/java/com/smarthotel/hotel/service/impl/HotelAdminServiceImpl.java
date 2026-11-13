package com.smarthotel.hotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smarthotel.hotel.entity.RoomType;

import com.smarthotel.hotel.mapper.RoomTypeMapper;
import com.smarthotel.hotel.service.HotelAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import com.smarthotel.hotel.model.RoomInventory;
import com.smarthotel.hotel.entity.RoomPrice;

import com.smarthotel.hotel.mapper.RoomPriceMapper;
import com.smarthotel.exception.BusinessException;

import java.time.LocalDate;
import com.smarthotel.dto.admin.UpdateInventoryRequest;
import com.smarthotel.dto.admin.UpdatePriceRequest;

import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HotelAdminServiceImpl
        implements HotelAdminService {

    private final RoomTypeMapper roomTypeMapper;



    private final com.smarthotel.hotel.service.InventoryReadGateway inventoryRead;

    private final RoomPriceMapper roomPriceMapper;



    /**
     * 管理员查看全部房型
     *
     * 包括已停售房型
     */
    @Override
    public List<RoomType> listRoomTypes() {

        return roomTypeMapper.selectList(
                new LambdaQueryWrapper<RoomType>()
                        .orderByAsc(
                                RoomType::getSortNo
                        )
        );
    }


    /**
     * 管理员查看实体客房
     *
     * 可按房型和房态筛选
     */


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

        if (roomTypeId == null) {
            throw new BusinessException(
                    400,
                    "房型不能为空"
            );
        }

        if (startDate == null || endDate == null) {
            throw new BusinessException(
                    400,
                    "开始日期和结束日期不能为空"
            );
        }

        if (startDate.isAfter(endDate)) {
            throw new BusinessException(
                    400,
                    "开始日期不能晚于结束日期"
            );
        }

        return roomPriceMapper.selectList(
                new LambdaQueryWrapper<RoomPrice>()
                        .eq(
                                RoomPrice::getRoomTypeId,
                                roomTypeId
                        )
                        .ge(
                                RoomPrice::getPriceDate,
                                startDate
                        )
                        .le(
                                RoomPrice::getPriceDate,
                                endDate
                        )
                        .orderByAsc(
                                RoomPrice::getPriceDate
                        )
        );
    }

    @Override
    @Transactional
    public void updatePrice(
            UpdatePriceRequest request) {

        /*
         * 1. 锁住当天房价
         */
        RoomPrice price =
                roomPriceMapper.selectOne(
                        new LambdaQueryWrapper<RoomPrice>()
                                .eq(
                                        RoomPrice::getRoomTypeId,
                                        request.getRoomTypeId()
                                )
                                .eq(
                                        RoomPrice::getPriceDate,
                                        request.getPriceDate()
                                )
                                .last("FOR UPDATE")
                );


        if (price == null) {

            throw new BusinessException(
                    404,
                    "该日期没有房价数据"
            );
        }


        /*
         * 2. 修改每日售价
         */
        price.setSalePrice(
                request.getSalePrice()
        );


        roomPriceMapper.updateById(
                price
        );
    }

}
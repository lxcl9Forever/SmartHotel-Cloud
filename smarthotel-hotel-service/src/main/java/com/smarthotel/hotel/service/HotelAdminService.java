package com.smarthotel.hotel.service;

import com.smarthotel.hotel.entity.RoomType;

import java.util.List;
import com.smarthotel.hotel.model.RoomInventory;
import com.smarthotel.hotel.entity.RoomPrice;

import java.time.LocalDate;
import com.smarthotel.dto.admin.UpdateInventoryRequest;
import com.smarthotel.dto.admin.UpdatePriceRequest;

public interface HotelAdminService {

    List<RoomType> listRoomTypes();



    List<RoomInventory> listInventories(
            Long roomTypeId,
            LocalDate startDate,
            LocalDate endDate
    );

    List<RoomPrice> listPrices(
            Long roomTypeId,
            LocalDate startDate,
            LocalDate endDate
    );

    void updatePrice(
            UpdatePriceRequest request
    );
}
package com.smarthotel.service;

import com.smarthotel.entity.Room;
import com.smarthotel.model.hotel.RoomType;

import java.util.List;
import com.smarthotel.model.inventory.RoomInventory;
import com.smarthotel.model.hotel.RoomPrice;

import java.time.LocalDate;
import com.smarthotel.dto.admin.UpdateInventoryRequest;
import com.smarthotel.dto.admin.UpdatePriceRequest;

public interface AdminHotelService {

    List<RoomType> listRoomTypes();

    List<Room> listRooms(
            Long roomTypeId,
            String status
    );

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

    void updateInventory(
            UpdateInventoryRequest request
    );

    void updatePrice(
            UpdatePriceRequest request
    );
}
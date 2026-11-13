package com.smarthotel.hotel.model;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data

public class RoomInventory {

    private Long inventoryId;

    private Long roomTypeId;

    private LocalDate inventoryDate;

    private Integer totalQty;

    private Integer lockedQty;

    private Integer soldQty;

    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
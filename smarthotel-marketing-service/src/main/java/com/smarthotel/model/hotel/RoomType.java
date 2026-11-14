package com.smarthotel.model.hotel;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data

public class RoomType {

    private Long roomTypeId;

    private String typeCode;

    private String typeName;

    private BigDecimal area;

    private String bedType;

    private Integer bedCount;

    private Integer maxGuests;

    private Integer totalRooms;

    private BigDecimal basePrice;

    private String description;

    private Integer status;

    private Integer sortNo;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
package com.smarthotel.model.hotel;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data

public class RoomPrice {

    private Long priceId;

    private Long roomTypeId;

    private LocalDate priceDate;

    private BigDecimal salePrice;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
package com.smarthotel.model.hotel;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Data

public class HotelInfo {

    private Long hotelId;

    private String hotelName;

    private String address;

    private String phone;

    private String description;

    private LocalTime checkInTime;

    private LocalTime checkOutTime;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
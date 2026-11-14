package com.smarthotel.model.hotel;

import lombok.Data;

import java.time.LocalDateTime;

@Data

public class RoomTypeImage {

    private Long imageId;

    private Long roomTypeId;

    private String imageUrl;

    private Integer isCover;

    private Integer sortNo;

    private LocalDateTime createTime;
}
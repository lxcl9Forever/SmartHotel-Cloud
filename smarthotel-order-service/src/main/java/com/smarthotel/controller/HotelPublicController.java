package com.smarthotel.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smarthotel.common.result.Result;
import com.smarthotel.model.hotel.HotelInfo;
import com.smarthotel.model.hotel.RoomType;
import com.smarthotel.model.hotel.RoomTypeImage;


import com.smarthotel.service.RoomTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/hotel")
@RequiredArgsConstructor
public class HotelPublicController {
    private final com.smarthotel.service.HotelCatalog catalog;
    private final RoomTypeService roomTypeService;
    

    @GetMapping
    public Result<HotelInfo> info() {
        HotelInfo hotel = catalog.hotel();
        return Result.success(hotel);
    }

    @GetMapping("/room-types/{roomTypeId}")
    public Result<Map<String, Object>> roomType(@PathVariable Long roomTypeId) {
        RoomType roomType = roomTypeService.getRoomTypeById(roomTypeId);
        List<RoomTypeImage> images = catalog.images(roomTypeId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomType", roomType);
        result.put("images", images);
        return Result.success(result);
    }
}

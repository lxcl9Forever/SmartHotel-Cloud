package com.smarthotel.controller;

import com.smarthotel.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public notice only: never expose arbitrary environment properties or credentials. */
@RestController
@RequiredArgsConstructor
public class HotelNoticeController {
    private final Environment environment;

    @GetMapping("/api/v1/hotel/notice")
    public Result<Map<String, String>> notice() {
        // Read on each request so Nacos Environment refresh is visible without restarting.
        return Result.success(Map.of("message",
                environment.getProperty("smarthotel.notice.message", "")));
    }
}

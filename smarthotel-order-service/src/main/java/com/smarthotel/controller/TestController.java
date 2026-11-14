package com.smarthotel.controller;

import com.smarthotel.common.result.Result;
import com.smarthotel.contract.UserDirectoryContract.Role;
import com.smarthotel.service.UserDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/test")
@RequiredArgsConstructor
public class TestController {

    private final JdbcTemplate jdbcTemplate;

    private final UserDirectory userDirectory;

    @GetMapping
    public Result<Map<String, Object>> test() {

        Map<String, Object> data = new HashMap<>();

        data.put("project", "SmartHotel");
        data.put("status", "running");

        return Result.success(data);
    }

    @GetMapping("/db")
    public Result<Map<String, Object>> databaseTest() {

        Integer result = jdbcTemplate.queryForObject(
                "SELECT 1",
                Integer.class
        );

        Map<String, Object> data = new HashMap<>();

        data.put("database", "MySQL");
        data.put("databaseName", jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
        data.put("status", "connected");
        data.put("result", result);

        return Result.success(data);
    }

    @GetMapping("/roles")
    public Result<List<Role>> roleTest() {

        List<Role> roles =
                userDirectory.roles();

        return Result.success(roles);
    }
}

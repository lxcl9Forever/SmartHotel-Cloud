// Historical test fixture only; excluded from the deployable application.
package com.smarthotel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smarthotel.fixture.entity.RoomInventory;
import com.smarthotel.mapper.BookingOrderMapper;
import com.smarthotel.mapper.RoomInventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Initial provisioning only. Existing order/refund ledgers need explicit reconciliation. */

@RequiredArgsConstructor
@Slf4j
public class FreshInventoryBootstrap implements ApplicationRunner {
    private final BookingOrderMapper orders;
    private final RoomInventoryMapper inventories;
    private final StringRedisTemplate redis;

    @Override
    public void run(ApplicationArguments args) {
        if (orders.selectCount(null) > 0) {
            log.info("Existing orders detected; leaving inventory cache unchanged");
            return;
        }
        var rows = inventories.selectList(new LambdaQueryWrapper<RoomInventory>()
                .ge(RoomInventory::getInventoryDate, LocalDate.now())
                .orderByAsc(RoomInventory::getRoomTypeId)
                .orderByAsc(RoomInventory::getInventoryDate));
        int initialized = 0;
        for (var row : rows) {
            int available = Math.max(0, row.getTotalQty() - row.getLockedQty() - row.getSoldQty());
            // SET NX never resets an already reserved Redis quantity, including on restart.
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                    "hotel:inventory:" + row.getRoomTypeId() + ":" + row.getInventoryDate(),
                    String.valueOf(available)))) {
                initialized++;
            }
        }
        log.info("Fresh-database inventory bootstrap initialized {} missing daily keys", initialized);
    }
}

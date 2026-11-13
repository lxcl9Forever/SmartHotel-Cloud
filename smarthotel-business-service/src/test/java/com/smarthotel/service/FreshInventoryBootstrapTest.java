package com.smarthotel.service;

import com.smarthotel.fixture.entity.RoomInventory;
import com.smarthotel.mapper.BookingOrderMapper;
import com.smarthotel.mapper.RoomInventoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.util.List;
import static org.mockito.Mockito.*;

class FreshInventoryBootstrapTest {
    private final BookingOrderMapper orders = mock(BookingOrderMapper.class);
    private final RoomInventoryMapper inventories = mock(RoomInventoryMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final FreshInventoryBootstrap bootstrap = new FreshInventoryBootstrap(orders, inventories, redis);

    @Test void freshDatabaseSeedsOnlyAbsentKeysFromSellableStock() {
        when(orders.selectCount(null)).thenReturn(0L);
        when(redis.opsForValue()).thenReturn(values);
        RoomInventory row = new RoomInventory();
        row.setRoomTypeId(7L); row.setInventoryDate(LocalDate.now());
        row.setTotalQty(20); row.setLockedQty(3); row.setSoldQty(4);
        when(inventories.selectList(any())).thenReturn(List.of(row));
        String key = "hotel:inventory:7:" + LocalDate.now();
        when(values.setIfAbsent(key, "13")).thenReturn(true, false);
        bootstrap.run(new DefaultApplicationArguments());
        bootstrap.run(new DefaultApplicationArguments());
        verify(values, times(2)).setIfAbsent(key, "13");
        verify(values, never()).set(anyString(), anyString());
    }

    @Test void databasesWithOrdersAreNeverAutomaticallyResynchronized() {
        when(orders.selectCount(null)).thenReturn(1L);
        bootstrap.run(new DefaultApplicationArguments());
        verifyNoInteractions(inventories, redis);
    }
}

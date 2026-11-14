package com.smarthotel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smarthotel.entity.BookingOrder;
import com.smarthotel.model.inventory.RoomInventory;
import com.smarthotel.mapper.BookingOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class OrderTimeoutService {
    private final BookingOrderMapper bookingOrderMapper;
    private final InventoryCommands inventoryCommands;
    private final DailyCheckinService dailyCheckinService;
    private final TransactionTemplate transactionTemplate;

    public OrderTimeoutService(BookingOrderMapper bookingOrderMapper,
                               InventoryCommands inventoryCommands,
                               DailyCheckinService dailyCheckinService,
                               PlatformTransactionManager transactionManager) {
        this.bookingOrderMapper = bookingOrderMapper;
        this.inventoryCommands = inventoryCommands;
        this.dailyCheckinService = dailyCheckinService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void scanExpired() {
        List<BookingOrder> candidates = bookingOrderMapper.selectList(
                new LambdaQueryWrapper<BookingOrder>()
                        .eq(BookingOrder::getOrderStatus, "WAIT_PAY")
                        .eq(BookingOrder::getPaymentStatus, "UNPAID")
                        .le(BookingOrder::getExpireTime, LocalDateTime.now())
                        .orderByAsc(BookingOrder::getExpireTime)
                        .last("LIMIT 200"));
        for (BookingOrder candidate : candidates) {
            try {
                closeIfExpired(candidate.getOrderId());
            } catch (RuntimeException error) {
                log.error("Failed to close expired order {}; next scan will retry", candidate.getOrderId(), error);
            }
        }
    }

    public void closeIfExpired(Long orderId) {
        transactionTemplate.executeWithoutResult(status -> closeLocked(orderId));
    }

    private void closeLocked(Long orderId) {
        BookingOrder order = bookingOrderMapper.selectOne(
                new LambdaQueryWrapper<BookingOrder>()
                        .eq(BookingOrder::getOrderId, orderId)
                        .last("FOR UPDATE"));
        if (order == null || order.getActiveWorkflowId()!=null || !"WAIT_PAY".equals(order.getOrderStatus())
                || !"UNPAID".equals(order.getPaymentStatus())
                || order.getExpireTime() == null
                || order.getExpireTime().isAfter(LocalDateTime.now())) {
            return;
        }

        inventoryCommands.cancel(order);
        order.setOrderStatus("CLOSED");
        bookingOrderMapper.updateById(order);
        dailyCheckinService.release(orderId);

    }
}

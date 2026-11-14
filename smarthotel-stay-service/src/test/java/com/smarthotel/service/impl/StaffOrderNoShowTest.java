package com.smarthotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smarthotel.entity.BookingOrder;
import com.smarthotel.exception.BusinessException;
import com.smarthotel.mapper.BookingGuestMapper;
import com.smarthotel.mapper.BookingOrderMapper;
import com.smarthotel.service.HotelCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class StaffOrderNoShowTest {
    private final BookingOrderMapper orders = mock(BookingOrderMapper.class);
    private final StaffOrderServiceImpl service = new StaffOrderServiceImpl(
            orders, mock(BookingGuestMapper.class), mock(HotelCatalog.class));

    @BeforeAll
    static void initializeEntityMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), BookingOrder.class);
    }

    @Test
    void futureOrRefundingOrderCannotBeMarkedNoShow() {
        BookingOrder order = eligibleOrder();
        order.setCheckInDate(LocalDate.now());
        when(orders.selectById(45L)).thenReturn(order);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.markNoShow(45L)).getCode());

        order.setCheckInDate(LocalDate.now().minusDays(1));
        order.setPaymentStatus("REFUNDING");
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.markNoShow(45L)).getCode());
        verify(orders, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void changedOrderIsNotOverwritten() {
        when(orders.selectById(45L)).thenReturn(eligibleOrder());
        when(orders.update(any(), any(Wrapper.class))).thenReturn(0);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.markNoShow(45L)).getCode());
    }

    @Test
    void bulkNoShowDelegatesEachOrderThroughFencedServiceAndSkipsStateConflicts() {
        var first=eligibleOrder();var second=eligibleOrder();second.setOrderId(46L);var third=eligibleOrder();third.setOrderId(47L);
        when(orders.selectList(any())).thenReturn(java.util.List.of(first,second,third));
        com.smarthotel.service.StaffOrderService fenced=mock(com.smarthotel.service.StaffOrderService.class);
        org.springframework.beans.factory.ObjectProvider<com.smarthotel.service.StaffOrderService> provider=mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(fenced);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"self",provider);
        doThrow(new BusinessException(409,"state changed")).when(fenced).markNoShow(46L);
        assertEquals(2,service.markOverdueNoShows());
        verify(fenced).markNoShow(45L);verify(fenced).markNoShow(46L);verify(fenced).markNoShow(47L);
        verify(orders,never()).update(any(),any(Wrapper.class));
    }

    private BookingOrder eligibleOrder() {
        BookingOrder order = new BookingOrder();
        order.setOrderId(45L);
        order.setOrderStatus("CONFIRMED");
        order.setPaymentStatus("PAID");
        order.setCheckInDate(LocalDate.now().minusDays(1));
        return order;
    }
}

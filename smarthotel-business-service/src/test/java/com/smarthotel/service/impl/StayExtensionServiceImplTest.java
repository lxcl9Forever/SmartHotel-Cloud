package com.smarthotel.service.impl;

import com.smarthotel.dto.stay.ExtendStayRequest;
import com.smarthotel.entity.BookingOrder;
import com.smarthotel.contract.DirectoryCustomer;
import com.smarthotel.entity.Payment;
import com.smarthotel.model.inventory.RoomInventory;
import com.smarthotel.model.hotel.RoomPrice;
import com.smarthotel.exception.BusinessException;
import com.smarthotel.mapper.BookingOrderMapper;
import com.smarthotel.service.UserDirectory;
import com.smarthotel.mapper.PaymentMapper;
import com.smarthotel.service.InventoryReadGateway;
import com.smarthotel.service.HotelCatalog;
import com.smarthotel.service.InventoryRedisService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StayExtensionServiceImplTest {
    private final BookingOrderMapper orders = mock(BookingOrderMapper.class);
    private final UserDirectory customers = mock(UserDirectory.class);
    private final HotelCatalog catalog = mock(HotelCatalog.class);
    private final InventoryReadGateway inventories = mock(InventoryReadGateway.class);
    private final com.smarthotel.service.InventoryCommands commands=mock(com.smarthotel.service.InventoryCommands.class);
    private final PaymentMapper payments = mock(PaymentMapper.class);
    private final StayExtensionServiceImpl service = new StayExtensionServiceImpl(
            customers, orders, catalog, inventories, commands, payments);

    @Test
    void customerCannotPreviewOrExtendAnotherCustomersOrder() {
        DirectoryCustomer customer = new DirectoryCustomer();
        customer.setCustomerId(11L);
        when(customers.findCustomerByUserId(any())).thenReturn(customer);
        BookingOrder order = checkedInOrder();
        order.setCustomerId(22L);
        when(orders.selectOne(any())).thenReturn(order);
        ExtendStayRequest request = request();

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.previewForCustomer(1L, request)).getCode());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.extendForCustomer(1L, request)).getCode());
        verifyNoInteractions(catalog, inventories, commands, payments);
    }

    @Test
    void extensionChargesAllRoomsAndReservesEachNewNight() {
        DirectoryCustomer customer = new DirectoryCustomer();
        customer.setCustomerId(11L);
        when(customers.findCustomerByUserId(any())).thenReturn(customer);
        BookingOrder order = checkedInOrder();
        when(orders.selectOne(any())).thenReturn(order);
        RoomPrice firstPrice = price("100.00");
        RoomPrice secondPrice = price("150.00");
        when(catalog.prices(3L, order.getCheckOutDate(), request().getNewCheckOutDate()))
                .thenReturn(List.of(firstPrice, secondPrice));
        RoomInventory firstDay = inventory();
        RoomInventory secondDay = inventory();
        when(inventories.query(any(),any(),any())).thenReturn(List.of(firstDay, secondDay));

        when(orders.updateById(any(BookingOrder.class))).thenReturn(1);
        when(payments.insert(any(Payment.class))).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            var result = service.extendForCustomer(1L, request());
            assertTrue(result.getSuccess());
            assertEquals(new BigDecimal("500.00"), result.getExtraAmount());


            assertEquals(new BigDecimal("900.00"), order.getTotalAmount());
            assertEquals(4, order.getNights());
            var payment = org.mockito.ArgumentCaptor.forClass(Payment.class);
            verify(payments).insert(payment.capture());
            assertEquals("SUCCESS", payment.getValue().getPaymentStatus());
            assertEquals(new BigDecimal("500.00"), payment.getValue().getAmount());
            verify(commands).extend(order, LocalDate.now().plusDays(3));

        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private BookingOrder checkedInOrder() {
        BookingOrder order = new BookingOrder();
        order.setOrderId(7L);
        order.setCustomerId(11L);
        order.setRoomTypeId(3L);
        order.setRoomCount(2);
        order.setCheckOutDate(LocalDate.now().plusDays(1));
        order.setNights(2);
        order.setTotalAmount(new BigDecimal("400.00"));
        order.setOrderStatus("CHECKED_IN");
        order.setPaymentStatus("PAID");
        return order;
    }

    private ExtendStayRequest request() {
        ExtendStayRequest request = new ExtendStayRequest();
        request.setOrderId(7L);
        request.setNewCheckOutDate(LocalDate.now().plusDays(3));
        return request;
    }

    private RoomPrice price(String amount) {
        RoomPrice price = new RoomPrice();
        price.setSalePrice(new BigDecimal(amount));
        return price;
    }

    private RoomInventory inventory() {
        RoomInventory day = new RoomInventory();
        day.setTotalQty(5);
        day.setLockedQty(0);
        day.setSoldQty(1);
        return day;
    }
}

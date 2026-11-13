package com.smarthotel.service.impl;

import com.smarthotel.dto.review.CreateReviewRequest;
import com.smarthotel.entity.BookingOrder;
import com.smarthotel.contract.DirectoryCustomer;
import com.smarthotel.exception.BusinessException;
import com.smarthotel.mapper.BookingOrderMapper;
import com.smarthotel.service.UserDirectory;
import com.smarthotel.mapper.OrderReviewMapper;
import com.smarthotel.vo.OrderReviewVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderReviewServiceImplTest {
    private final OrderReviewMapper reviewMapper = mock(OrderReviewMapper.class);
    private final BookingOrderMapper orderMapper = mock(BookingOrderMapper.class);
    private final UserDirectory customerMapper = mock(UserDirectory.class);
    private OrderReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderReviewServiceImpl(customerMapper, reviewMapper, orderMapper);
    }

    @Test
    void checkedOutOwnerCanReviewOnce() {
        arrangeOrder(1L, "CHECKED_OUT");
        CreateReviewRequest request = request();
        service.create(30L, 3L, request);
        verify(reviewMapper).insert(30L, 1L, 5, "入住很好");
    }

    @Test
    void wrongCustomerCannotReview() {
        arrangeOrder(2L, "CHECKED_OUT");
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(30L, 3L, request()));
        assertEquals(404, error.getCode());
        verify(reviewMapper, never()).insert(any(), any(), any(), any());
    }

    @Test
    void activeStayCannotReview() {
        arrangeOrder(1L, "CHECKED_IN");
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(30L, 3L, request()));
        assertEquals(409, error.getCode());
        verify(reviewMapper, never()).insert(any(), any(), any(), any());
    }

    @Test
    void alreadyReviewedOrderCannotReviewAgain() {
        arrangeOrder(1L, "COMPLETED");
        when(reviewMapper.selectByOrderId(eq(30L))).thenReturn(new OrderReviewVO());
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(30L, 3L, request()));
        assertEquals(409, error.getCode());
        verify(reviewMapper, never()).insert(any(), any(), any(), any());
    }

    private void arrangeOrder(Long orderCustomerId, String status) {
        DirectoryCustomer customer = new DirectoryCustomer();
        customer.setCustomerId(1L);
        when(customerMapper.findCustomerByUserId(any())).thenReturn(customer);
        if (orderCustomerId.equals(1L)) {
            BookingOrder order = new BookingOrder();
            order.setOrderId(30L);
            order.setCustomerId(1L);
            order.setOrderStatus(status);
            when(orderMapper.selectOne(any())).thenReturn(order);
        }
    }

    private CreateReviewRequest request() {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating(5);
        request.setContent(" 入住很好 ");
        return request;
    }
}

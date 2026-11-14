package com.smarthotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smarthotel.entity.BookingOrder;
import com.smarthotel.model.hotel.RoomType;
import com.smarthotel.exception.BusinessException;
import com.smarthotel.mapper.BookingGuestMapper;
import com.smarthotel.mapper.BookingOrderMapper;
import com.smarthotel.service.StaffOrderService;
import com.smarthotel.vo.StaffOrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.smarthotel.entity.BookingGuest;
import com.smarthotel.vo.BookingGuestVO;

import com.smarthotel.entity.*;
import com.smarthotel.model.hotel.RoomType;
import com.smarthotel.mapper.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class StaffOrderServiceImpl
        implements StaffOrderService {

    private final BookingOrderMapper bookingOrderMapper;

    private final BookingGuestMapper bookingGuestMapper;

    private final com.smarthotel.service.HotelCatalog catalog;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<StaffOrderService> self;


    @Override
    public List<StaffOrderVO> listOrders(
            String status) {

        LambdaQueryWrapper<BookingOrder> wrapper =
                new LambdaQueryWrapper<>();

        /*
         * 如果传了状态，就按状态过滤
         */
        if (status != null
                && !status.isBlank()) {

            wrapper.eq(
                    BookingOrder::getOrderStatus,
                    status
            );
        }

        wrapper.orderByDesc(
                BookingOrder::getCreateTime
        );


        List<BookingOrder> orders =
                bookingOrderMapper.selectList(
                        wrapper
                );


        List<StaffOrderVO> result =
                new ArrayList<>();


        var roomTypes=catalog.types(orders.stream().map(BookingOrder::getRoomTypeId).toList());
        for (BookingOrder order : orders) {

            StaffOrderVO vo =
                    new StaffOrderVO();

            vo.setOrderId(
                    order.getOrderId()
            );

            vo.setOrderNo(
                    order.getOrderNo()
            );

            vo.setRoomTypeId(
                    order.getRoomTypeId()
            );

            RoomType roomType =
                    roomTypes.get(order.getRoomTypeId());

            if (roomType != null) {
                vo.setRoomTypeName(
                        roomType.getTypeName()
                );
            }

            vo.setRoomCount(
                    order.getRoomCount()
            );

            vo.setGuestCount(
                    order.getGuestCount()
            );

            vo.setCheckInDate(
                    order.getCheckInDate()
            );

            vo.setCheckOutDate(
                    order.getCheckOutDate()
            );

            vo.setTotalAmount(
                    order.getTotalAmount()
            );

            vo.setContactName(
                    order.getContactName()
            );

            vo.setContactPhone(
                    order.getContactPhone()
            );

            vo.setOrderStatus(
                    order.getOrderStatus()
            );

            vo.setPaymentStatus(
                    order.getPaymentStatus()
            );

            vo.setCreateTime(
                    order.getCreateTime()
            );

            result.add(vo);
        }

        return result;
    }

    @Override
    public List<BookingGuestVO> listOrderGuests(
            Long orderId) {

        /*
         * 1. 确认订单存在
         */
        BookingOrder order =
                bookingOrderMapper.selectById(
                        orderId
                );

        if (order == null) {

            throw new BusinessException(
                    404,
                    "订单不存在"
            );
        }


        /*
         * 2. 查询入住人
         */
        List<BookingGuest> guests =
                bookingGuestMapper.selectList(
                        new LambdaQueryWrapper<BookingGuest>()
                                .eq(
                                        BookingGuest::getOrderId,
                                        orderId
                                )
                                .orderByDesc(
                                        BookingGuest::getIsPrimary
                                )
                                .orderByAsc(
                                        BookingGuest::getGuestId
                                )
                );


        /*
         * 3. Entity -> VO
         */
        List<BookingGuestVO> result =
                new ArrayList<>();


        for (BookingGuest guest : guests) {

            BookingGuestVO vo =
                    new BookingGuestVO();

            vo.setGuestId(
                    guest.getGuestId()
            );

            vo.setGuestName(
                    guest.getGuestName()
            );

            vo.setPhone(
                    guest.getPhone()
            );

            vo.setIdType(
                    guest.getIdType()
            );

            vo.setIdNumber(
                    guest.getIdNumber()
            );

            vo.setIsPrimary(
                    guest.getIsPrimary()
            );


            result.add(vo);
        }


        return result;
    }

    @Override
    @Transactional
    public void completeOrder(Long orderId){

        BookingOrder order =
                bookingOrderMapper.selectById(orderId);


        if(order == null){

            throw new BusinessException(
                    404,
                    "订单不存在"
            );

        }


        if(!"CHECKED_OUT".equals(order.getOrderStatus())){

            throw new BusinessException(
                    400,
                    "只有退房订单可以完成"
            );

        }


        order.setOrderStatus(
                "COMPLETED"
        );


        bookingOrderMapper.updateById(order);

    }

    @Override
    @Transactional
    public void markNoShow(Long orderId) {
        BookingOrder order = bookingOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (!"CONFIRMED".equals(order.getOrderStatus())
                || !"PAID".equals(order.getPaymentStatus())
                || order.getCheckInDate() == null
                || !order.getCheckInDate().isBefore(LocalDate.now())) {
            throw new BusinessException(409, "只有已过入住日期且已支付的待入住订单可以标记未入住");
        }
        int updated = bookingOrderMapper.update(null, overdueNoShowCondition()
                .eq(BookingOrder::getOrderId, orderId));
        if (updated != 1) {
            throw new BusinessException(409, "订单状态已变化，请刷新后重试");
        }
    }

    @Override
    public int markOverdueNoShows() {
        var candidates=bookingOrderMapper.selectList(new LambdaQueryWrapper<BookingOrder>().eq(BookingOrder::getOrderStatus,"CONFIRMED").eq(BookingOrder::getPaymentStatus,"PAID").lt(BookingOrder::getCheckInDate,LocalDate.now()).last("LIMIT 200"));
        int completed=0;for(var order:candidates){try{self.getObject().markNoShow(order.getOrderId());completed++;}catch(BusinessException conflict){if(conflict.getCode()!=409)throw conflict;}}return completed;
    }

    private LambdaUpdateWrapper<BookingOrder> overdueNoShowCondition() {
        return new LambdaUpdateWrapper<BookingOrder>()
                .eq(BookingOrder::getOrderStatus, "CONFIRMED")
                .eq(BookingOrder::getPaymentStatus, "PAID")
                .lt(BookingOrder::getCheckInDate, LocalDate.now())
                .set(BookingOrder::getOrderStatus, "NO_SHOW");
    }
}

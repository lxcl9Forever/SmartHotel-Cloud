package com.smarthotel.service;
import com.smarthotel.entity.*;
import com.smarthotel.mapper.*;
import com.smarthotel.exception.BusinessException;
import com.smarthotel.vo.BookingOrderVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import java.time.LocalDateTime;
import java.util.UUID;
@Service @RequiredArgsConstructor public class MockPaymentService {
  private final UserDirectory users;
  private final BookingOrderMapper orders;
  private final PaymentMapper payments;
  private final InventoryCommands inventory;
  @Transactional public BookingOrderVO pay(String username,Long orderId) {
    Long customer=users.requireCustomerId(username);
    var order=orders.selectById(orderId);
    if(order==null||!customer.equals(order.getCustomerId()))throw new BusinessException(404,"订单不存在");
    if(!"WAIT_PAY".equals(order.getOrderStatus())||!"UNPAID".equals(order.getPaymentStatus())||order.getExpireTime()==null||!order.getExpireTime().isAfter(LocalDateTime.now()))throw new BusinessException(409,"当前订单不能支付");
    inventory.confirm(order);
    Payment payment=new Payment();
    payment.setOrderId(orderId);
    payment.setPaymentNo(UUID.randomUUID().toString().replace("-","").toUpperCase());
    payment.setPaymentMethod("MOCK");
    payment.setPaymentStatus("SUCCESS");
    payment.setAmount(order.getTotalAmount());
    payment.setPaidTime(LocalDateTime.now());
    payments.insert(payment);
    order.setOrderStatus("CONFIRMED");
    order.setPaymentStatus("PAID");
    orders.updateById(order);
    var result=new BookingOrderVO();
    org.springframework.beans.BeanUtils.copyProperties(order,result);
    return result;
  }
}

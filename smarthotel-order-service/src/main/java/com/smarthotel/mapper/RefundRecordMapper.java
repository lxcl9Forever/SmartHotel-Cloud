package com.smarthotel.mapper;
import com.smarthotel.entity.RefundRecord;import org.apache.ibatis.annotations.*;
@Mapper public interface RefundRecordMapper {
 @Select("SELECT * FROM refund_snapshot WHERE order_id=#{orderId} ORDER BY refund_id DESC LIMIT 1") RefundRecord selectByOrderId(Long orderId);
}

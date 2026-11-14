package com.smarthotel.mapper;

import com.smarthotel.vo.StaffStayHistoryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface StaffStayHistoryMapper {
    @Select("select count(*) from check_in_record")
    long countAll();

    @Select("""
        select ci.check_in_id as checkInId, o.order_no as orderNo,
               o.contact_name as contactName, r.room_no as roomNo, r.floor_no as floorNo,
               r.room_type_id as roomTypeId, ci.actual_check_in_time as actualCheckInTime,
               co.actual_check_out_time as actualCheckOutTime,
               ci.handled_by as checkInUserId, co.handled_by as checkOutUserId
        from check_in_record ci
        join room_assignment a on a.assignment_id = ci.assignment_id
        join order_snapshot o on o.order_id = a.order_id
        join room r on r.room_id = a.room_id
        left join check_out_record co on co.check_in_id = ci.check_in_id
        order by ci.actual_check_in_time desc, ci.check_in_id desc
        limit #{offset}, #{pageSize}
        """)
    List<StaffStayHistoryVO> list(@Param("offset") long offset, @Param("pageSize") int pageSize);
}

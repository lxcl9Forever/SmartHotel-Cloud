// Historical test fixture only; excluded from the deployable application.
package com.smarthotel.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smarthotel.fixture.entity.RoomInventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;



public interface RoomInventoryMapper
        extends BaseMapper<RoomInventory> {


    @Update("""
            update room_inventory
            set sold_qty = sold_qty + #{count},
                update_time = now()
            where room_type_id = #{roomTypeId}
            and inventory_date = #{date}
            """)
    int increaseSoldQty(
            Long roomTypeId,
            LocalDate date,
            Integer count
    );

}
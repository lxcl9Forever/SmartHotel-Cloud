package com.smarthotel.contract;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Common must not make service-owned persistence types available transitively. */
class CommonModelOwnershipTest {
    @Test void serviceOwnedModelsAreAbsentFromCommonClasspath() {
        for (String name : "Customer CustomerSavedGuest Employee MemberLevel SysRole SysUser BookingGuest BookingOrder CheckInRecord CheckOutRecord DailyDeal HotelDailyStatistics Payment Promotion RefundPaymentRecord RefundRecord Room RoomAssignment RoomStatusLog HotelInfo RoomType RoomTypeImage RoomPrice RoomInventory OperationLogRecord".split(" ")) {
            assertThatThrownBy(() -> Class.forName("com.smarthotel.entity." + name))
                .as("service-owned entity %s", name).isInstanceOf(ClassNotFoundException.class);
        }
        assertThatThrownBy(() -> Class.forName("com.smarthotel.vo.AdminOrderDetailVO"))
            .isInstanceOf(ClassNotFoundException.class);
    }
}

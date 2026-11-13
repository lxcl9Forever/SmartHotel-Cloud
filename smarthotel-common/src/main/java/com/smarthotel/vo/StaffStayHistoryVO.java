package com.smarthotel.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class StaffStayHistoryVO {
    private Long checkInId;
    private String orderNo;
    private String contactName;
    private String roomNo;
    private Integer floorNo;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long roomTypeId;
    private String roomTypeName;
    private LocalDateTime actualCheckInTime;
    private LocalDateTime actualCheckOutTime;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long checkInUserId;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long checkOutUserId;
    private String checkInStaff;
    private String checkOutStaff;
}

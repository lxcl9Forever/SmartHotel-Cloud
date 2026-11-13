package com.smarthotel.contract;
import lombok.Data;
/** Stable customer ID mapping owned by the user service. */
@Data
public class DirectoryCustomer {
    private Long customerId;
    private Long userId;
    private String customerName;
}

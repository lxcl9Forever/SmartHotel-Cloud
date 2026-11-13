package com.smarthotel.contract;
import lombok.Data;
/** Minimal account reference; no password, phone, or identity-document fields. */
@Data
public class DirectoryAccount {
    private Long userId;
    private String username;
}

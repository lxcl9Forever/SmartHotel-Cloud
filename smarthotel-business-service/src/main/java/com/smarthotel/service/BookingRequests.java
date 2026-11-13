package com.smarthotel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarthotel.dto.booking.CreateBookingRequest;
import com.smarthotel.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

@Service @RequiredArgsConstructor
public class BookingRequests {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private String key(CreateBookingRequest request) {
        if(request.getRequestId()==null)return null;
        String key=request.getRequestId().trim().toLowerCase(Locale.ROOT);
        if(!key.matches("[a-z0-9:_-]{1,64}"))throw new BusinessException(400,"无效的预订请求编号");
        return key;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Long begin(Long customer,CreateBookingRequest request) {
        String key=key(request);if(key==null)return null;
        try {
            var node=json.valueToTree(request);((com.fasterxml.jackson.databind.node.ObjectNode)node).remove("requestId");
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(node)));
            jdbc.update("INSERT INTO booking_request(customer_id,request_key,payload_hash) VALUES(?,?,?) ON DUPLICATE KEY UPDATE request_key=request_key",customer,key,hash);
            var row=jdbc.queryForMap("SELECT payload_hash,order_id FROM booking_request WHERE customer_id=? AND request_key=? FOR UPDATE",customer,key);
            if(!hash.equals(row.get("payload_hash")))throw new BusinessException(409,"同一个预订请求编号不能用于不同的预订内容");
            Number id=(Number)row.get("order_id");return id==null?null:id.longValue();
        }catch(BusinessException error){throw error;}catch(Exception error){throw new IllegalStateException("Unable to claim booking request",error);}
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void complete(Long customer,CreateBookingRequest request,Long order) {
        String key=key(request);if(key==null)return;
        if(jdbc.update("UPDATE booking_request SET order_id=? WHERE customer_id=? AND request_key=? AND order_id IS NULL",order,customer,key)!=1)
            throw new IllegalStateException("Booking request binding failed");
    }
}

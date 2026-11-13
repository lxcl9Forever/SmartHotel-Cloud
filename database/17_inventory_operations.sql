USE smart_hotel_cloud;
CREATE TABLE IF NOT EXISTS booking_request (
 customer_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 order_id BIGINT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(customer_id,request_key),
 UNIQUE KEY uk_booking_request_order(order_id),
 CONSTRAINT fk_booking_request_order FOREIGN KEY(order_id) REFERENCES booking_order(order_id)
);
CREATE TABLE IF NOT EXISTS inventory_operation (
 operation_key VARCHAR(96) NOT NULL PRIMARY KEY,
 order_id BIGINT NOT NULL,
 operation_type VARCHAR(48) NOT NULL,
 request_signature VARCHAR(200) NOT NULL,
 state VARCHAR(16) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 completed_at DATETIME NULL,
 KEY idx_inventory_operation_order(order_id),
 CONSTRAINT fk_inventory_operation_order FOREIGN KEY(order_id) REFERENCES booking_order(order_id)
);
CREATE TABLE IF NOT EXISTS inventory_cache_sync (
 room_type_id BIGINT NOT NULL,
 inventory_date DATE NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 revision BIGINT NOT NULL DEFAULT 1,
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_error VARCHAR(120) NULL,
 synced_at DATETIME NULL,
 PRIMARY KEY(room_type_id,inventory_date),
 KEY idx_inventory_cache_pending(state,inventory_date),
 CONSTRAINT fk_inventory_cache_type FOREIGN KEY(room_type_id) REFERENCES room_type(room_type_id)
);

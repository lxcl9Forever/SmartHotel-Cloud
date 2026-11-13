USE smart_hotel_cloud;
CREATE TABLE IF NOT EXISTS inventory_reservation (
 order_id BIGINT NOT NULL PRIMARY KEY,
 room_type_id BIGINT NOT NULL,
 check_in_date DATE NOT NULL,
 check_out_date DATE NOT NULL,
 room_count INT NOT NULL,
 state VARCHAR(24) NOT NULL,
 pending_attempt_id VARCHAR(36) NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS inventory_tcc_attempt (
 attempt_id VARCHAR(36) NOT NULL PRIMARY KEY,
 order_id BIGINT NOT NULL,
 operation_key VARCHAR(96) NOT NULL,
 payload_json TEXT NOT NULL,
 phase VARCHAR(16) NOT NULL,
 no_op TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_inventory_tcc_order(order_id)
);
CREATE TABLE IF NOT EXISTS inventory_tcc_journal (
 attempt_id VARCHAR(36) NOT NULL PRIMARY KEY,
 order_id BIGINT NOT NULL,
 payload_json TEXT NOT NULL,
 decision VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(120) NULL,
 next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_inventory_journal_pending(state,next_attempt_at)
);

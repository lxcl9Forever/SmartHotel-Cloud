CREATE TABLE IF NOT EXISTS payment_channel_audit (
 payment_id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,state VARCHAR(32) NOT NULL,
 gateway_status VARCHAR(40),trade_no VARCHAR(64),received_amount DECIMAL(12,2),
 note VARCHAR(500),needs_attention TINYINT NOT NULL DEFAULT 0,
 last_checked_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),refunded_time DATETIME(6),
 KEY idx_channel_attention(needs_attention,last_checked_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

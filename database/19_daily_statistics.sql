USE smart_hotel_cloud;
CREATE TABLE IF NOT EXISTS hotel_daily_statistics (
 stat_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 stat_date DATE NOT NULL,
 new_order_count INT NOT NULL DEFAULT 0,
 check_in_count INT NOT NULL DEFAULT 0,
 check_out_count INT NOT NULL DEFAULT 0,
 occupied_room_count INT NOT NULL DEFAULT 0,
 available_room_count INT NOT NULL DEFAULT 0,
 occupancy_rate DECIMAL(12,4) NOT NULL DEFAULT 0,
 room_revenue DECIMAL(18,2) NOT NULL DEFAULT 0,
 extra_revenue DECIMAL(18,2) NOT NULL DEFAULT 0,
 refund_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
 total_revenue DECIMAL(18,2) NOT NULL DEFAULT 0,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_hotel_daily_statistics_date(stat_date)
);

-- Missing legacy stay-operation tables, required for a fresh installation.
USE smart_hotel_cloud;

CREATE TABLE IF NOT EXISTS room_assignment (
    assignment_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    assigned_by BIGINT NOT NULL,
    assign_time DATETIME NOT NULL,
    effective_from DATETIME NOT NULL,
    effective_to DATETIME NULL,
    assignment_status VARCHAR(32) NOT NULL,
    change_reason VARCHAR(500),
    INDEX idx_assignment_order_status (order_id, assignment_status),
    INDEX idx_assignment_room_status (room_id, assignment_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS check_in_record (
    check_in_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    assignment_id BIGINT NOT NULL,
    primary_guest_id BIGINT,
    actual_check_in_time DATETIME NOT NULL,
    deposit_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    handled_by BIGINT NOT NULL,
    remark VARCHAR(500),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_check_in_assignment (assignment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS check_out_record (
    check_out_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    check_in_id BIGINT NOT NULL,
    actual_check_out_time DATETIME NOT NULL,
    room_charge DECIMAL(12,2) NOT NULL DEFAULT 0,
    extra_charge DECIMAL(12,2) NOT NULL DEFAULT 0,
    refund_deposit DECIMAL(12,2) NOT NULL DEFAULT 0,
    settlement_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    handled_by BIGINT NOT NULL,
    remark VARCHAR(500),
    UNIQUE KEY uk_check_out_check_in (check_in_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room_status_log (
    log_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    old_status VARCHAR(32),
    new_status VARCHAR(32) NOT NULL,
    change_reason VARCHAR(500),
    operator_user_id BIGINT,
    change_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_room_status_time (room_id, change_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- 订单服务库：yd_order
-- ============================================================
CREATE DATABASE IF NOT EXISTS yd_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_order;

-- 预约订单表
CREATE TABLE IF NOT EXISTS t_booking (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no      VARCHAR(32)   NOT NULL COMMENT '对外订单号',
    user_id       BIGINT        NOT NULL COMMENT '用户 id',
    course_id     BIGINT        NOT NULL COMMENT '课程 id',
    activity_id   BIGINT        NULL COMMENT '秒杀活动 id（普通预约为 NULL）',
    course_name   VARCHAR(50)   NOT NULL COMMENT '课程名(冗余快照)',
    coach         VARCHAR(20)   NOT NULL COMMENT '教练(冗余快照)',
    book_time     VARCHAR(20)   NOT NULL COMMENT '上课时间文案',
    store_name    VARCHAR(50)   NOT NULL COMMENT '门店名(冗余快照)',
    price         DECIMAL(10,2) NOT NULL COMMENT '实付价（秒杀单为秒杀价）',
    status        VARCHAR(10)   NOT NULL DEFAULT 'UNPAID' COMMENT 'UNPAID/BOOKED/FINISHED/CANCELED',
    cancel_reason VARCHAR(50)   NULL COMMENT '取消原因',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    pay_time      DATETIME      NULL COMMENT '支付时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_status (user_id, status),
    KEY idx_course_user (course_id, user_id, status)   -- 同人同课去重查询
) ENGINE = InnoDB COMMENT '预约订单表';

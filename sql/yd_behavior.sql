-- ============================================================
-- 埋点服务库：yd_behavior
-- ============================================================
CREATE DATABASE IF NOT EXISTS yd_behavior DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_behavior;

-- 行为日志明细表（Kafka 消费落库，只写不改）
CREATE TABLE IF NOT EXISTS t_behavior_log (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    event_id   VARCHAR(64) NOT NULL COMMENT '事件唯一 id（幂等键）',
    event_type VARCHAR(20) NOT NULL COMMENT 'page_view/course_click/seckill_click/checkin_click',
    target_id  BIGINT      NULL COMMENT '目标 id（课程 id 等）',
    device_id  VARCHAR(64) NOT NULL COMMENT '设备 id',
    user_id    BIGINT      NULL COMMENT '用户 id（匿名 NULL）',
    client_ts  BIGINT      NULL COMMENT '客户端时间戳(毫秒)',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event (event_id),              -- 幂等兜底
    KEY idx_type_target (event_type, target_id), -- 热度统计
    KEY idx_device (device_id)
) ENGINE = InnoDB COMMENT '行为日志明细表';

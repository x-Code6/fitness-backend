-- ============================================================
-- 积分服务库：yd_point
-- ============================================================
CREATE DATABASE IF NOT EXISTS yd_point DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_point;

-- 会员积分汇总表（每用户一行）
CREATE TABLE IF NOT EXISTS t_member_points (
    user_id         BIGINT      NOT NULL COMMENT '用户 id（业务主键）',
    balance         INT         NOT NULL DEFAULT 0 COMMENT '积分余额',
    total_checkin   INT         NOT NULL DEFAULT 0 COMMENT '累计打卡天数',
    continuous_days INT         NOT NULL DEFAULT 0 COMMENT '连续打卡天数',
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
) ENGINE = InnoDB COMMENT '会员积分汇总表';

-- 积分流水表
CREATE TABLE IF NOT EXISTS t_points_log (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT      NOT NULL COMMENT '用户 id',
    title      VARCHAR(50) NOT NULL COMMENT '流水标题',
    points     INT         NOT NULL COMMENT '积分变动(正加负减)',
    biz_type   VARCHAR(20) NOT NULL COMMENT 'CHECKIN/COURSE_FINISHED/POST_PUBLISHED/EXCHANGE',
    biz_id     VARCHAR(64) NULL COMMENT '业务幂等 id（如完课事件 eventId）',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz (biz_type, biz_id),      -- 幂等兜底：同一业务事件只入账一次
    KEY idx_user (user_id, created_at)
) ENGINE = InnoDB COMMENT '积分流水表';

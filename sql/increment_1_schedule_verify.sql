-- ============================================================
-- 第一批增量：课程场次 + 预约核销码（在已有库上执行增量脚本）
-- ============================================================

-- ---------- 课程库：新增场次表 ----------
USE yd_course;

CREATE TABLE IF NOT EXISTS t_course_schedule (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '场次 id',
    course_id     BIGINT      NOT NULL COMMENT '课程 id',
    schedule_time DATETIME    NOT NULL COMMENT '上课时间',
    capacity      INT         NOT NULL DEFAULT 20 COMMENT '本场容量',
    booked        INT         NOT NULL DEFAULT 0 COMMENT '本场已约',
    status        TINYINT     NOT NULL DEFAULT 1 COMMENT '1 可约 0 已取消',
    PRIMARY KEY (id),
    KEY idx_course_time (course_id, schedule_time)
) ENGINE = InnoDB COMMENT '课程场次表';

-- 给每门在售课程种 3 个未来场次（时间相对 NOW() 生成，演示永远有效）
INSERT INTO t_course_schedule (course_id, schedule_time, capacity, booked)
SELECT c.id,
       DATE_ADD(NOW(), INTERVAL (c.id * 10 + s.n) HOUR),
       c.capacity,
       LEAST(FLOOR(c.capacity * 0.6), c.capacity - 1)
FROM t_course c
JOIN (SELECT 1 AS n UNION SELECT 2 UNION SELECT 3) s
WHERE c.status = 1;

-- ---------- 订单库：预约单增加场次与核销字段 ----------
USE yd_order;

ALTER TABLE t_booking
    ADD COLUMN schedule_id  BIGINT      NULL COMMENT '场次 id' AFTER activity_id,
    ADD COLUMN verify_code  VARCHAR(16) NULL COMMENT '核销码（支付成功后生成）',
    ADD COLUMN verified_at  DATETIME    NULL COMMENT '核销时间',
    ADD UNIQUE KEY uk_verify_code (verify_code);

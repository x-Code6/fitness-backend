-- ============================================================
-- 2026-10-21 · 退款按张 + 同人时段唯一 + 完课事件补偿
-- 归属库：yd_order、yd_course
--
-- 【这次修的三件事】
-- 1) 多凭证订单（买 2 份 = 2 张凭证）以前任意一张没用过的凭证过期，
--    就把整张订单置为 REFUNDED；用户上过课也能拿全款。
--    现在：每张凭证记下自己分摊的实付金额 paid_amount，过期时按张退；
--    订单累计退款额达到实付额才置 REFUNDED，否则保持 PAID（部分退款）。
-- 2) 同一用户同一小时的预约，只靠 Java 先查后插挡不住并发；
--    加 (user_id, start_time) 唯一键，让数据库兜底。
-- 3) 完课事件发布失败时，视频进度已经 finished=1，之后不会再发；
--    加 finish_event_sent 标记，由定时任务补偿重发。
--
-- 【执行说明】
-- MySQL 的 ALTER 不支持 IF NOT EXISTS；重复执行本脚本时
-- “Duplicate column name / Duplicate key name”表示已经做过了，不是失败。
-- ============================================================

USE yd_order;

-- ---------------------------------------------------------------------------
-- 第 1 步：体检。必须返回 0 行，才能加 uk_user_start。
-- 有输出说明历史上同一用户同一小时已经有多条占用，先人工处理重复行。
-- ---------------------------------------------------------------------------
SELECT user_id, start_time, COUNT(*) AS duplicate_rows
  FROM t_coach_busy
 GROUP BY user_id, start_time
HAVING COUNT(*) > 1;

-- ---------------------------------------------------------------------------
-- 第 2 步：订单加累计退款额
-- ---------------------------------------------------------------------------
ALTER TABLE t_booking
    ADD COLUMN refund_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00
        COMMENT '累计已退款金额。达到 pay_amount 时订单才置 REFUNDED；小于时订单仍是 PAID（部分退款）'
        AFTER refunded;

-- ---------------------------------------------------------------------------
-- 第 3 步：凭证加"分摊实付金额 + 已退金额 + 退款时间"
-- ---------------------------------------------------------------------------
ALTER TABLE t_entitlement
    ADD COLUMN paid_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00
        COMMENT '出票时按份数分摊到的实付金额。退款以它为准'
        AFTER used_times;

ALTER TABLE t_entitlement
    ADD COLUMN refund_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00
        COMMENT '已退款金额。一张凭证最多退一次'
        AFTER paid_amount;

ALTER TABLE t_entitlement
    ADD COLUMN refunded_at DATETIME NULL
        COMMENT '退款时间；NULL = 未退款'
        AFTER refund_amount;

-- 历史数据回填：按订单实付 / 份数分摊。
-- ★这是近似值：3 份除不尽时每张都四舍五入，三张之和可能与订单实付差 1 分。
--   新数据由 Java 出票时分配（余数给最后一张），不会再有这个问题。
UPDATE t_entitlement e
  JOIN t_booking b ON e.order_no = b.order_no
   SET e.paid_amount = ROUND(b.pay_amount / b.quantity, 2)
 WHERE e.paid_amount = 0
   AND b.quantity > 0;

-- 历史 REFUNDED 订单：旧逻辑是整单退，但那时没有 refund_amount 列。
-- 补成实付额，否则看板的“累计退款”会把这批历史退款算丢。
UPDATE t_booking
   SET refund_amount = pay_amount
 WHERE status = 'REFUNDED'
   AND refund_amount = 0;

-- 历史“过期且未使用”的凭证：旧逻辑整单退过，凭证侧没有按张退款记录。
-- 这里补上退款标记，避免升级后过期任务再把它当成“可退”处理一遍。
UPDATE t_entitlement e
  JOIN t_booking b ON e.order_no = b.order_no
   SET e.refund_amount = COALESCE(e.paid_amount, 0),
       e.refunded_at = COALESCE(b.refund_at, NOW())
 WHERE b.status = 'REFUNDED'
   AND e.status = 'EXPIRED'
   AND e.used_times = 0
   AND e.refunded_at IS NULL;

-- ---------------------------------------------------------------------------
-- 第 4 步：同一用户同一小时只能有一条占用
-- ---------------------------------------------------------------------------
ALTER TABLE t_coach_busy
    ADD UNIQUE KEY uk_user_start (user_id, start_time);

-- ---------------------------------------------------------------------------
-- 第 5 步：验证 yd_order 的三处改动
-- ---------------------------------------------------------------------------
SELECT column_name, column_type, column_default
  FROM information_schema.columns
 WHERE table_schema = 'yd_order'
   AND table_name IN ('t_booking', 't_entitlement')
   AND column_name IN ('refund_amount', 'paid_amount', 'refunded_at')
 ORDER BY table_name, column_name;

SELECT index_name, column_name, non_unique
  FROM information_schema.statistics
 WHERE table_schema = 'yd_order'
   AND table_name = 't_coach_busy'
   AND index_name = 'uk_user_start';

-- ---------------------------------------------------------------------------
-- 第 6 步：月榜归档查询索引（yd_point）
-- 归档改为从流水重算后，每天都会按 created_at 范围扫一次
-- ---------------------------------------------------------------------------
USE yd_point;

ALTER TABLE t_points_log
    ADD KEY idx_created (created_at);

-- ---------------------------------------------------------------------------
-- 第 7 步：完课事件补偿标记（yd_course）
-- ---------------------------------------------------------------------------
USE yd_course;

ALTER TABLE t_video_progress
    ADD COLUMN finish_event_sent TINYINT NOT NULL DEFAULT 0
        COMMENT '完课事件是否已成功发送；0=待补偿重发，1=已发送'
        AFTER finished_at;

ALTER TABLE t_video_progress
    ADD COLUMN first_report_at DATETIME NULL
        COMMENT '首次上报时间。防刷按“从首次上报到现在的总时长”限制允许进度'
        AFTER finish_event_sent;

-- 回填 first_report_at：按当前进度估算已经看了多久，把起点往前推。
-- 否则升级后老用户的 first_report_at 都等于最近一次上报时间，
-- 允许进度从 15% 重新开始，会出现“看到 80% 了却上报不了”的误伤。
UPDATE t_video_progress p
  JOIN t_video v ON p.video_id = v.id
   SET p.first_report_at = DATE_SUB(
       COALESCE(p.last_report_at, p.updated_at, NOW()),
       INTERVAL FLOOR(COALESCE(v.duration, 60) * COALESCE(p.progress, 0) / 100) MINUTE)
 WHERE p.first_report_at IS NULL;

-- 已有 finished=1 的记录默认是 0，会被补偿任务重新发一遍；
-- 消费端按 userId:videoId 幂等，重复发送不会重复加积分。
SELECT id, user_id, video_id, finished, finish_event_sent
  FROM t_video_progress
 WHERE finished = 1 AND finish_event_sent = 0
 LIMIT 20;

-- ============================================================
-- 悦动健身 v2 · 埋点域 yd_behavior
-- 归属服务：fitness-behavior (8106)
-- 链路：前端攒批上报 → Kafka(yd-behavior-event, key=deviceId) → 消费落库
--       → course_click 事件同时 ZINCRBY 热度榜 → 首页按热度重排推荐
-- ============================================================

CREATE DATABASE IF NOT EXISTS yd_behavior
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_behavior;

-- ----------------------------------------------------------------
-- 行为日志明细表（append-only：只 INSERT，永不 UPDATE）
-- 日志类表就该这样，所以它天然适合按时间分区/上冷存储（本项目演示量级不做分区）。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_behavior_log (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    event_id   VARCHAR(64)  NOT NULL COMMENT '事件唯一 id，前端生成（幂等键）',
    event_type VARCHAR(20)  NOT NULL COMMENT 'page_view 页面浏览 / course_click 课程卡点击 / seckill_click 抢课点击 / checkin_click 打卡点击。入参用 @Pattern 白名单校验，禁止任意字符串',
    target_id  BIGINT       NULL     COMMENT '目标 id（如课程 id）。page_view 时为 NULL',
    device_id  VARCHAR(64)  NOT NULL COMMENT '★设备 id，前端生成存 localStorage，未登录也有值。埋点从用户打开首页就开始了，那时他还没登录',
    user_id    BIGINT       NULL     COMMENT '用户 id，登录后才有值。★设备与账号的归因靠这两列：同一 device_id 后来登录了某个 user_id，就把匿名行为和实名行为串起来',
    props      JSON         NULL     COMMENT '★事件扩展属性（v1 的架构文档写了这列但没实现，v2 补上）。例：{"from":"home_banner","tab":"燃脂"}。用 JSON 类型而不是 TEXT：MySQL 8 能在函数索引里直接取键值',
    client_ts  BIGINT       NULL     COMMENT '★客户端时间戳（毫秒），来自用户手机，可被篡改、时钟可能不准；用于分析行为时序。与下面 created_at 语义完全不同，两个都要留',
    server_ts  BIGINT       NULL     COMMENT '服务端接收时间（毫秒）。与 client_ts 差值可监控时钟漂移与上报延迟',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间（可信，用于对账与数据分区）',
    PRIMARY KEY (id),
    -- 幂等的 DB 兜底：Redis SETNX behavior:seen:{eventId} 是快路径，
    -- 这条唯一索引保证即使 Redis 异常也不会重复统计。
    -- ★注意：v1 里 DuplicateKeyException 没被单独捕获，落进了大 catch 只打日志，
    --   而消费者用的是默认 auto-commit，offset 照样前进 → 消息静默丢失。v2 要改手动 ack。
    UNIQUE KEY uk_event (event_id),
    -- 热度统计与"某类事件按目标聚合"
    KEY idx_type_target (event_type, target_id, created_at),
    -- 排查"这台设备的行为轨迹"
    KEY idx_device (device_id, created_at),
    KEY idx_user (user_id, created_at)
) ENGINE = InnoDB COMMENT '行为日志明细表';

-- ============================================================
-- 种子数据
-- ============================================================
-- 埋点表【故意不预置数据】：热度榜 behavior:hot:course:{yyyyMM} 是靠真实点击
-- 事件 ZINCRBY 出来的，塞假数据会让"点击后首页推荐变化"这条演示链路失真。
-- 演示方法：H5 首页多点几门课 → 查 Redis 热度榜 → 刷新首页看推荐顺序变化。
-- 需要清空重来的时候：TRUNCATE t_behavior_log; 并删掉 Redis 的 behavior:hot:* key。

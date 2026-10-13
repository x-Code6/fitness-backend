-- ============================================================
-- 悦动健身 v2 · 积分域 yd_point
-- 归属服务：fitness-point (8104)
-- 职责：每日打卡、积分余额与流水、月度排行榜（含归档）、积分商城
-- ============================================================

CREATE DATABASE IF NOT EXISTS yd_point
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_point;

-- ----------------------------------------------------------------
-- 会员积分汇总表（每用户一行）
-- 用 user_id 直接做主键，不再塞一个自增 id：
--   ① 这是一对一关系，user_id 天然唯一；
--   ② 查询永远是 WHERE user_id=?，用 user_id 做主键=聚簇索引直接命中，省一次回表。
--   InnoDB 里主键就是数据存放顺序，把最高频查询条件当主键是标准优化。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_member_points (
    user_id         BIGINT   NOT NULL COMMENT '用户 id，同时作为主键（跨库 → yd_user.t_user.id）',
    balance         INT      NOT NULL DEFAULT 0 COMMENT '积分余额。★余额不靠 SUM(流水) 算：流水只增不改，每次算不可扩展。流水是账本，这张表是物化结果，必须用 SQL 原子自增更新，不能 Java 里读-改-写',
    total_checkin   INT      NOT NULL DEFAULT 0 COMMENT '累计打卡天数',
    continuous_days INT      NOT NULL DEFAULT 0 COMMENT '连续打卡天数。★数据来源是 Redis Bitmap 算出的快照，不是 DB 自己算的（DB 里没有日期序列可用）',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
) ENGINE = InnoDB COMMENT '会员积分汇总表';

-- ----------------------------------------------------------------
-- 积分流水表（append-only，只 INSERT 不 UPDATE）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_points_log (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT      NOT NULL COMMENT '谁',
    title      VARCHAR(50) NOT NULL COMMENT '流水标题，直接给前端展示，如"每日打卡""完课奖励"',
    points     INT         NOT NULL COMMENT '变动值，★正加负减（一张表同时记收支，靠符号区分）',
    biz_type   VARCHAR(20) NOT NULL COMMENT 'CHECKIN 打卡 / COURSE_FINISHED 完课 / EXCHANGE 兑换 / ADMIN 后台调整',
    biz_id     VARCHAR(64) NULL COMMENT '★业务幂等 id。打卡=日期串"2026-10-13"；完课=派生业务键"userId:videoId"（v1 用的是每次新生成的 UUID，导致幂等完全失效，v2 必须改）；兑换=giftId:时间戳',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '时间',
    PRIMARY KEY (id),
    -- ★幂等的最终防线：Redis SETNX 是快路径，但 Redis 挂了/过期了/并发穿透了，
    --   第二次 insert 会撞这个唯一键抛 DuplicateKeyException，代码捕获后按"已处理"ack。
    --   biz_type 必须参与复合键，否则打卡的日期串可能与某个 eventId 相撞而误判。
    UNIQUE KEY uk_biz (biz_type, biz_id),
    -- "积分明细"分页：WHERE user_id=? ORDER BY created_at DESC
    KEY idx_user (user_id, created_at)
) ENGINE = InnoDB COMMENT '积分流水表';

-- ----------------------------------------------------------------
-- 每日打卡记录（v2 新增，修掉"打卡明细只存在于 Redis"）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_checkin_record (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT   NOT NULL COMMENT '谁',
    check_date DATE     NOT NULL COMMENT '打卡日期',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '打卡时刻（可看具体几点打的卡）',
    PRIMARY KEY (id),
    -- ★★★v2 用这张表替代"GETBIT 判重 + SETBIT"的非原子写法：
    --   打卡流程第一步就是往这里 INSERT，撞唯一键就说明今天已经打过。
    --   唯一索引是数据库级保证，比 Redis 两次命令之间的时间窗可靠，
    --   也不需要 Lua 或分布式锁（并发防御三档里"必须防"这一档的最省事解法）。
    --   同时它让 Redis Bitmap 退化成"可重建的展示缓存"，Redis 清空不再丢业务数据。
    UNIQUE KEY uk_user_date (user_id, check_date)
) ENGINE = InnoDB COMMENT '每日打卡记录（打卡真相的 DB 副本）';

-- ----------------------------------------------------------------
-- 月度排行榜归档（★单表 + season 字段，不采用"每月一个表"）
-- 每月一表的问题：一年后 12 张同构表（索引/备份/统计全重复）；
--   查询要动态拼表名（MyBatis @TableName 失效、注入面自己扛）；
--   跨月趋势要 UNION；新月份的表谁建、建失败怎么办都得管。
--   课程里天机学堂的分片归档也是写入同一张归档表，靠 season 区分。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_rank_archive (
    season        CHAR(6)  NOT NULL COMMENT '归档月份 yyyyMM，如 202610',
    user_id       BIGINT   NOT NULL COMMENT '用户 id',
    score         INT      NOT NULL COMMENT '该月最终积分',
    rank_no       INT      NOT NULL COMMENT '归档时的名次。存下来是为了"并列名次按打卡天数二次排序"这类规则固化，展示时不必重算',
    total_checkin INT      NOT NULL DEFAULT 0 COMMENT '该月累计打卡天数',
    continuous_best INT    NOT NULL DEFAULT 0 COMMENT '该月最长连续打卡',
    archived_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
    PRIMARY KEY (season, user_id),
    -- "查某月 TopN"直接走这个索引，等值(season)在前、排序(score DESC)在后
    KEY idx_season_score (season, score DESC),
    KEY idx_user (user_id, season)
) ENGINE = InnoDB COMMENT '月度排行榜归档（本月读 Redis，历史月读这张表）';

-- ----------------------------------------------------------------
-- 积分商城商品
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_gift (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(50)  NOT NULL COMMENT '商品名，如"运动毛巾"',
    points_cost INT          NOT NULL COMMENT '兑换所需积分',
    stock       INT          NOT NULL DEFAULT 0 COMMENT '库存。兑换扣减用 UPDATE...WHERE stock>0 原子判定，不做读-改-写',
    image       VARCHAR(200) NULL COMMENT '商品图相对路径 /uploads/xxx.png，管理端上传',
    description VARCHAR(200) NULL COMMENT '商品说明，如"到店领取"',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1 上架 / 0 下架',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status (status, points_cost)
) ENGINE = InnoDB COMMENT '积分商城商品表';

-- ============================================================
-- 种子数据（演示账号 id 10001 / 10002，见 01_yd_user.sql 末尾约定）
-- ============================================================

-- 10001 攒了一些分并有账户行；10002 故意不建账户行，用来验证"首次打卡/首次兑换自动建汇总行"这条分支
INSERT INTO t_member_points (user_id, balance, total_checkin, continuous_days) VALUES
(10001, 320, 12, 3);

INSERT INTO t_points_log (user_id, title, points, biz_type, biz_id, created_at) VALUES
(10001, '每日打卡', 10, 'CHECKIN', DATE_FORMAT(NOW() - INTERVAL 3 DAY, '%Y-%m-%d'), NOW() - INTERVAL 3 DAY),
(10001, '每日打卡', 10, 'CHECKIN', DATE_FORMAT(NOW() - INTERVAL 2 DAY, '%Y-%m-%d'), NOW() - INTERVAL 2 DAY),
(10001, '每日打卡', 10, 'CHECKIN', DATE_FORMAT(NOW() - INTERVAL 1 DAY, '%Y-%m-%d'), NOW() - INTERVAL 1 DAY),
(10001, '完课奖励', 30, 'COURSE_FINISHED', '10001:1', NOW() - INTERVAL 2 DAY),
(10001, '完课奖励', 30, 'COURSE_FINISHED', '10001:3', NOW() - INTERVAL 1 DAY),
(10001, '兑换扣减', -200, 'EXCHANGE', '1:1760000000000', NOW() - INTERVAL 5 DAY);

-- 打卡记录（与上面的 CHECKIN 流水对得上）
INSERT INTO t_checkin_record (user_id, check_date, created_at) VALUES
(10001, DATE_FORMAT(NOW() - INTERVAL 3 DAY, '%Y-%m-%d'), NOW() - INTERVAL 3 DAY + INTERVAL 8 HOUR),
(10001, DATE_FORMAT(NOW() - INTERVAL 2 DAY, '%Y-%m-%d'), NOW() - INTERVAL 2 DAY + INTERVAL 7 HOUR),
(10001, DATE_FORMAT(NOW() - INTERVAL 1 DAY, '%Y-%m-%d'), NOW() - INTERVAL 1 DAY + INTERVAL 21 HOUR);

-- 上月榜归档示例：让"查看历史月榜"接口一上来就有数据可演示
INSERT INTO t_rank_archive (season, user_id, score, rank_no, total_checkin, continuous_best) VALUES
('202609', 10001, 480, 1, 22, 9),
('202609', 10002, 360, 2, 16, 5),
('202609', 10003, 290, 3, 12, 4);

INSERT INTO t_gift (id, name, points_cost, stock, image, description, status) VALUES
(1, '运动毛巾',        200,  50, NULL, '到店前台领取', 1),
(2, '蛋白粉体验装',    800,  20, NULL, '到店领取，一次一袋', 1),
(3, '私教体验课 1 节', 1500, 10, NULL, '兑换后由门店联系预约，高新店/大学城店可用', 1),
(4, '品牌运动水杯',    1200, 30, NULL, '到店领取', 1),
(5, '月度会员卡（已下架示例）', 5000, 0, NULL, '状态 0 不出现在列表里', 0);

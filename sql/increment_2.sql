-- ============================================================
-- 第二批增量：优惠券 / 关注关系 / 积分商城 / 管理员库（含操作日志）
-- ============================================================

-- ---------- 课程服务库：优惠券 ----------
USE yd_course;

CREATE TABLE IF NOT EXISTS t_coupon (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    title        VARCHAR(50)   NOT NULL COMMENT '券名，如 新人立减券',
    threshold    DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '使用门槛（满 X 元可用，0 无门槛）',
    discount     DECIMAL(10,2) NOT NULL COMMENT '抵扣金额',
    total        INT           NOT NULL COMMENT '发放总量',
    remain       INT           NOT NULL COMMENT '剩余可领',
    limit_per_user INT         NOT NULL DEFAULT 1 COMMENT '每人限领（当前实现固定 1）',
    start_time   DATETIME      NOT NULL COMMENT '可领开始时间',
    end_time     DATETIME      NOT NULL COMMENT '可领结束时间（同时是使用截止）',
    status       TINYINT       NOT NULL DEFAULT 1 COMMENT '1 上架 0 下架 2 作废',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT '优惠券表';

CREATE TABLE IF NOT EXISTS t_user_coupon (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '用户持券记录 id',
    user_id     BIGINT      NOT NULL COMMENT '持有人',
    coupon_id   BIGINT      NOT NULL COMMENT '券 id',
    status      VARCHAR(10) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED/USED',
    order_no    VARCHAR(32) NULL COMMENT '核销订单号',
    received_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    used_at     DATETIME    NULL COMMENT '使用时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_coupon (user_id, coupon_id),   -- 一人一张
    KEY idx_user_status (user_id, status)
) ENGINE = InnoDB COMMENT '用户持券表';

-- ---------- 社区服务库：关注关系 ----------
USE yd_community;

CREATE TABLE IF NOT EXISTS t_follow (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT      NOT NULL COMMENT '关注人',
    followee_id BIGINT      NOT NULL COMMENT '被关注人',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_follow (user_id, followee_id),
    KEY idx_followee (followee_id)
) ENGINE = InnoDB COMMENT '关注关系表';

-- ---------- 积分服务库：积分商城 ----------
USE yd_point;

CREATE TABLE IF NOT EXISTS t_gift (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name       VARCHAR(50)  NOT NULL COMMENT '商品名，如 运动毛巾',
    points_cost INT         NOT NULL COMMENT '兑换所需积分',
    stock      INT          NOT NULL DEFAULT 0 COMMENT '库存',
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 上架 0 下架',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT '积分商城商品表';

INSERT INTO t_gift (id, name, points_cost, stock, status) VALUES
(1, '运动毛巾', 200, 50, 1),
(2, '蛋白粉体验装', 800, 20, 1),
(3, '私教体验课 1 节', 1500, 10, 1),
(4, '品牌运动水杯', 1200, 30, 1);

-- ---------- 管理员库（含操作日志） ----------
CREATE DATABASE IF NOT EXISTS yd_admin DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_admin;

CREATE TABLE IF NOT EXISTS t_admin (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(20)  NOT NULL COMMENT '登录名',
    password      VARCHAR(100) NOT NULL COMMENT 'BCrypt 散列',
    real_name     VARCHAR(20)  NOT NULL COMMENT '姓名',
    role          VARCHAR(10)  NOT NULL DEFAULT 'OPERATOR' COMMENT 'SUPER 超管 / OPERATOR 运营',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 禁用',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT '管理员表';

CREATE TABLE IF NOT EXISTS t_operate_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    admin_id    BIGINT       NOT NULL COMMENT '操作人 id',
    admin_name  VARCHAR(20)  NOT NULL COMMENT '操作人姓名',
    module      VARCHAR(20)  NOT NULL COMMENT '业务模块',
    operation   VARCHAR(50)  NOT NULL COMMENT '操作描述',
    method      VARCHAR(200) NULL COMMENT '请求方法',
    params      VARCHAR(500) NULL COMMENT '参数(JSON 截断)',
    ip          VARCHAR(40)  NULL COMMENT '操作 IP',
    duration_ms BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时(毫秒)',
    success     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否成功',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_admin (admin_id, created_at)
) ENGINE = InnoDB COMMENT '管理端操作日志表';

-- 演示管理员：admin/123456(SUPER)、operator/123456(OPERATOR，无会员/门店/日志权限)
INSERT INTO t_admin (username, password, real_name, role, status) VALUES
('admin',    '$2b$10$zLynAorzCUF8CG85gqHEyO5nINjpreoQ8y.bZXuYQpPGWv89UjDsC', '运营管理员', 'SUPER',    1),
('operator', '$2b$10$zLynAorzCUF8CG85gqHEyO5nINjpreoQ8y.bZXuYQpPGWv89UjDsC', '内容运营',   'OPERATOR', 1);

-- ---------- 订单库：预约单增加优惠券字段 ----------
USE yd_order;
ALTER TABLE t_booking
    ADD COLUMN coupon_record_id BIGINT       NULL COMMENT '使用的用户持券记录 id' AFTER schedule_id,
    ADD COLUMN discount_amount  DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '优惠金额' AFTER price;

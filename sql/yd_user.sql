-- ============================================================
-- 用户服务库：yd_user
-- 执行方式：mysql -uroot -p < yd_user.sql
-- ============================================================
CREATE DATABASE IF NOT EXISTS yd_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_user;

-- 用户表
CREATE TABLE IF NOT EXISTS t_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    phone       VARCHAR(11)  NOT NULL                COMMENT '手机号（登录账号）',
    password    VARCHAR(100) NOT NULL                COMMENT 'BCrypt 密码散列',
    nickname    VARCHAR(20)  NOT NULL                COMMENT '昵称',
    avatar_color VARCHAR(2)  NOT NULL DEFAULT '0'    COMMENT '头像配色下标 0~5',
    status      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态 1 正常 0 禁用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone)          -- 唯一索引：手机号查重的最终兜底（并发注册安全）
) ENGINE = InnoDB COMMENT '用户表';

-- 演示账号（密码为 123456 的 BCrypt 散列，可直接用于登录测试）
INSERT INTO t_user (phone, password, nickname, avatar_color, status)
VALUES ('13800008819', '$2b$10$zLynAorzCUF8CG85gqHEyO5nINjpreoQ8y.bZXuYQpPGWv89UjDsC', '小鱼同学', '0', 1);

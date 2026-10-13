-- ============================================================
-- 悦动健身 v2 · 用户库 yd_user
-- 归属服务：fitness-user (8101)
-- 职责：账号与登录、站内消息
--
-- 【执行顺序】文件名前缀 01~06 是给 MySQL 容器初始化目录用的。
--   docker-entrypoint-initdb.d 里的 .sql 按文件名字母序执行，
--   旧脚本 increment_* 会排在建表脚本之前导致新环境初始化失败，
--   所以这里统一改成数字前缀（修 Git 提交前的核查问题 P0-8）。
--
-- 【手工执行】mysql -uroot -p < 01_yd_user.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS yd_user
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_user;

-- ----------------------------------------------------------------
-- 用户表
-- 说明：v2 里"登录"仍然是账号密码（手机号 + BCrypt 口令）。
--       旧架构文档写的"手机号验证码登录"从未实现，本轮不保留这个说法。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，全站对外的用户标识，JWT subject 装它',
    phone         VARCHAR(11)  NOT NULL                COMMENT '手机号，登录账号',
    password      VARCHAR(100) NOT NULL                COMMENT 'BCrypt 散列，绝不存明文；长度 100 是给算法升级留余量',
    nickname      VARCHAR(20)  NOT NULL                COMMENT '昵称，注册时按"悦动同学+手机号后4位"自动生成',
    avatar_color  VARCHAR(2)   NOT NULL DEFAULT '0'    COMMENT '头像配色下标 0~5，无图时的兜底方案（前端 AVATAR_COLORS 第几号渐变）',
    avatar_image  VARCHAR(200) NULL                    COMMENT '头像图片相对路径，如 /uploads/2026/10/xxx.png；NULL 表示用 avatar_color 兜底',
    status        TINYINT      NOT NULL DEFAULT 1      COMMENT '1 正常 / 0 禁用。作用=禁止新登录；已在线用户的立即下线靠 Redis 令牌白名单，不靠这一列',
    last_login_at DATETIME     NULL                    COMMENT '最后登录时间，管理端会员列表要展示',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 唯一索引：并发注册时"先查再插"不是原子的，这条索引是最终防线。
    -- 第二个并发请求 insert 会抛 DuplicateKeyException，代码捕获后返回"手机号已注册"。
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB COMMENT '用户表';

-- ----------------------------------------------------------------
-- 站内消息表
-- 设计要点：消息的"产生方"是订单/积分/课程三个服务，但它们不能跨库写这张表。
--          统一走 RabbitMQ（ex.notify.exchange / q.user.notify），由 user 服务消费后落库，
--          即"写操作收口到数据 owner"。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_message (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT       NOT NULL                COMMENT '接收人（逻辑外键 → t_user.id）',
    title      VARCHAR(50)  NOT NULL                COMMENT '消息标题，如"预约创建成功"',
    content    VARCHAR(200) NOT NULL                COMMENT '消息正文',
    msg_type   VARCHAR(20)  NOT NULL                COMMENT 'BOOKING 订单 / RESERVATION 预约 / POINT 积分 / SYSTEM 系统',
    biz_no     VARCHAR(32)  NULL                    COMMENT '关联业务单号（订单号或预约单号），前端"点击查看"跳转用',
    is_read    TINYINT      NOT NULL DEFAULT 0      COMMENT '0 未读 / 1 已读',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    PRIMARY KEY (id),
    -- 服务两条查询：①"我的未读列表按时间倒序" ②"未读数统计"。
    -- 字段顺序=先等值(user_id,is_read)再排序(created_at)，顺序反了就用不上索引排序。
    KEY idx_user_read (user_id, is_read, created_at)
) ENGINE = InnoDB COMMENT '站内消息表';

-- ============================================================
-- 种子数据说明
-- ============================================================
-- 本文件【故意不预置任何用户账号】。
-- 原因：BCrypt 散列串必须与"实际加密器"匹配。旧脚本里那串 $2b$10$... 来自
--       非 Java 工具，$2b 前缀能否被 Spring Security 的 BCryptPasswordEncoder
--       校验通过从未被验证过（这套库此前从没导入运行过）。
--       所以改由代码初始化：fitness-user 启动时用 passwordEncoder.encode("123456")
--       写入演示账号（见第 2 期 DemoAccountInitializer），保证散列与加密器一定同源。
--
-- ★约定：演示账号的 id 由初始化器【显式指定】，不与其它表协商失败：
--       10001 = 主演示号（手机 13800008819，口令 123456）
--       10002 = 第二个演示号（手机 13800008820，口令 123456）
--   03_yd_order.sql 里的历史订单/凭证/预约/评价都引用这两个 id，
--   所以初始化器必须按同样的 id 插入（MySQL 对 AUTO_INCREMENT 主键可以显式给值），
--   否则种子数据找不到人，"我的订单""评分"首屏全空。

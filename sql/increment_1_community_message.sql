-- ============================================================
-- 第一批增量：社区服务库（动态/评论/点赞）+ 用户消息表
-- ============================================================

-- ---------- 社区服务库 ----------
CREATE DATABASE IF NOT EXISTS yd_community DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_community;

-- 动态表
CREATE TABLE IF NOT EXISTS t_post (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT       NOT NULL COMMENT '作者 id',
    user_name   VARCHAR(20)  NOT NULL COMMENT '作者昵称(快照)',
    avatar_color VARCHAR(2)  NOT NULL DEFAULT '0' COMMENT '作者头像配色(快照)',
    content     VARCHAR(500) NOT NULL COMMENT '正文',
    images      VARCHAR(100) NULL COMMENT '配图（渐变色下标 csv，真实项目为 OSS url）',
    topic       VARCHAR(20)  NOT NULL DEFAULT '打卡日常' COMMENT '话题',
    like_count  INT          NOT NULL DEFAULT 0 COMMENT '点赞数',
    comment_count INT        NOT NULL DEFAULT 0 COMMENT '评论数',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 已删除',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user (user_id, status),
    KEY idx_time (created_at)
) ENGINE = InnoDB COMMENT '动态表';

-- 点赞表（一人一动态一个赞）
CREATE TABLE IF NOT EXISTS t_post_like (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    post_id    BIGINT      NOT NULL COMMENT '动态 id',
    user_id    BIGINT      NOT NULL COMMENT '点赞人',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_post_user (post_id, user_id)
) ENGINE = InnoDB COMMENT '动态点赞表';

-- 评论表（两级：parent_id 为空是一级评论，否则是回复）
CREATE TABLE IF NOT EXISTS t_comment (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    post_id       BIGINT       NOT NULL COMMENT '动态 id',
    user_id       BIGINT       NOT NULL COMMENT '评论人 id',
    user_name     VARCHAR(20)  NOT NULL COMMENT '评论人昵称(快照)',
    content       VARCHAR(200) NOT NULL COMMENT '评论内容',
    parent_id     BIGINT       NULL COMMENT '父评论 id（回复某条评论时非空）',
    reply_to_user VARCHAR(20)  NULL COMMENT '被回复人昵称（展示"回复 @xx"）',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 已删除',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_post (post_id, status, created_at)
) ENGINE = InnoDB COMMENT '动态评论表';

-- ---------- 用户库：站内消息表 ----------
USE yd_user;

CREATE TABLE IF NOT EXISTS t_message (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT       NOT NULL COMMENT '接收人',
    title      VARCHAR(50)  NOT NULL COMMENT '消息标题',
    content    VARCHAR(200) NOT NULL COMMENT '消息内容',
    msg_type   VARCHAR(20)  NOT NULL COMMENT 'BOOKING/POINT/INTERACTION',
    is_read    TINYINT      NOT NULL DEFAULT 0 COMMENT '0 未读 1 已读',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_read (user_id, is_read, created_at)
) ENGINE = InnoDB COMMENT '站内消息表';

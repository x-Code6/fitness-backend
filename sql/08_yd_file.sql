-- ============================================================
-- 悦动健身 v2 · 文件域 yd_file
-- 归属服务：fitness-file (8108)
-- 职责：图片上传的元数据（磁盘只存二进制，这里存"谁在什么时候传了什么"）
--
-- ★为什么单独一个库：文件服务是"写入磁盘 + 元数据"的独立生命周期，
--   和课程/订单/用户库没有联表需求（只存 uploader_id，不建外键），
--   独立后文件服务的连接池、备份、清理任务都不会影响交易库。
--
-- ★为什么元数据表要存 path 和 url 两份：
--   path（category/uuid.ext）是给业务表落库的稳定相对路径；
--   url（/uploads/category/uuid.ext）是给前端直接展示的访问路径。
--   将来换域名/上 CDN 只改 url 前缀，业务表里的 path 不用刷全表。
--
-- 本文件按 MySQL 容器字母序执行，前缀 08 排在 07_yd_community 之后。
-- ============================================================

CREATE DATABASE IF NOT EXISTS yd_file
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_file;

-- ----------------------------------------------------------------
-- 文件元数据表
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_file (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    biz_category  VARCHAR(20)  NOT NULL COMMENT '业务分类（落盘子目录）：course/store/coach/gift/video/community/banner/avatar/post/common',
    file_path     VARCHAR(300) NOT NULL COMMENT '相对路径 category/uuid.ext，业务表落库用（换域名不影响）',
    file_url      VARCHAR(300) NOT NULL COMMENT '访问路径 /uploads/category/uuid.ext，前端展示用',
    content_type  VARCHAR(100) NULL     COMMENT 'multipart 上报的 Content-Type，浏览器可伪造，只作参考',
    size_bytes    BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    uploader_type VARCHAR(10)  NOT NULL COMMENT '上传者类型：ADMIN 管理端 / USER 用户端',
    uploader_id   BIGINT       NULL     COMMENT '上传者 id（管理员 id 或用户 id）。跨库只存 id，不建外键',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (id),
    -- ★同一相对路径只能有一条记录：UUID 文件名理论上不会撞，但唯一键是"元数据不重复"的最终防线
    UNIQUE KEY uk_file_path (file_path),
    -- 管理端按业务分类筛"最近上传"：等值列在前、范围/排序列在后
    KEY idx_category_time (biz_category, created_at),
    -- "某个管理员/用户传过哪些图"：审计与被删除用户的内容清理都要走这条索引
    KEY idx_uploader (uploader_type, uploader_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '文件元数据表';

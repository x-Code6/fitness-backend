-- ============================================================
-- 2026-10-21 · 文件服务 + 首页活动轮播 + 帖子配图
-- 归属库：yd_file（新建）、yd_course、yd_community、yd_user
--
-- 【这次做的三件事】
-- 1) 新建独立文件服务库 yd_file，所有图片（课程/门店/教练/礼品/头像/海报/帖子）
--    统一走 fitness-file 上传，业务表只存相对路径。
-- 2) 课程/秒杀/优惠券可以在保存时勾选"上架活动页"，t_banner 存海报与排序；
--    来源下架/结束时查询层会过滤，不依赖管理员记得手动下架。
-- 3) 帖子配图从"渐变下标"改为真实图片路径列表，csv 列从 VARCHAR(200) 放宽到 1000。
--
-- 【执行说明】
-- MySQL 的 ALTER 不支持 IF NOT EXISTS；重复执行本脚本时
-- “Duplicate column name / Table already exists”表示已经做过了，不是失败。
-- 建议按顺序执行：先建库建表，再改列。
-- ============================================================

-- ---------------------------------------------------------------------------
-- 第 1 步：文件元数据库与表
-- ---------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS yd_file
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_file;

CREATE TABLE IF NOT EXISTS t_file (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    biz_category  VARCHAR(20)  NOT NULL COMMENT '业务分类（落盘子目录）：course/store/coach/gift/video/community/banner/avatar/post/common',
    file_path     VARCHAR(300) NOT NULL COMMENT '相对路径 category/uuid.ext，业务表落库用',
    file_url      VARCHAR(300) NOT NULL COMMENT '访问路径 /uploads/category/uuid.ext，前端展示用',
    content_type  VARCHAR(100) NULL     COMMENT 'multipart 上报的 Content-Type，只作参考',
    size_bytes    BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    uploader_type VARCHAR(10)  NOT NULL COMMENT 'ADMIN 管理端 / USER 用户端',
    uploader_id   BIGINT       NULL     COMMENT '上传者 id。跨库只存 id，不建外键',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_path (file_path),
    KEY idx_category_time (biz_category, created_at),
    KEY idx_uploader (uploader_type, uploader_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '文件元数据表';

-- ---------------------------------------------------------------------------
-- 第 2 步：首页活动轮播表
-- ---------------------------------------------------------------------------
USE yd_course;

CREATE TABLE IF NOT EXISTS t_banner (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title      VARCHAR(64)  NULL     COMMENT '海报主标题，空则前端回退来源名称',
    subtitle   VARCHAR(128) NULL     COMMENT '副标题/一句话卖点',
    image_url  VARCHAR(255) NOT NULL COMMENT '海报访问路径 /uploads/banner/xxx.png',
    link_type  VARCHAR(16)  NOT NULL COMMENT 'COURSE / SECKILL / COUPON',
    target_id  BIGINT       NOT NULL COMMENT '来源 id：课程 id / 秒杀活动 id / 券 id',
    sort_no    INT          NOT NULL DEFAULT 0 COMMENT '越小越靠前',
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 上架 / 0 下架',
    start_time DATETIME     NULL     COMMENT '展示开始时间，空=立即',
    end_time   DATETIME     NULL     COMMENT '展示结束时间，空=长期',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_link_target (link_type, target_id),
    KEY idx_status_sort (status, sort_no)
) ENGINE = InnoDB COMMENT '首页活动轮播';

-- ---------------------------------------------------------------------------
-- 第 3 步：帖子配图列放宽
-- 旧列 200 字符只够存 3~4 个真实路径；9 张图最多约 900 字符
-- ---------------------------------------------------------------------------
USE yd_community;

ALTER TABLE t_post
    MODIFY COLUMN images VARCHAR(1000) NULL
        COMMENT '配图访问路径 csv，如 /uploads/post/a.png,/uploads/post/b.png（最多 9 张）';

-- ---------------------------------------------------------------------------
-- 第 4 步：用户头像列
-- 01_yd_user.sql 的新库已经包含该列；老库需要补。执行前可先跑下面的体检：
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA='yd_user' AND TABLE_NAME='t_user' AND COLUMN_NAME='avatar_image';
-- 已存在时跳过本段。
-- ---------------------------------------------------------------------------
USE yd_user;

ALTER TABLE t_user
    ADD COLUMN avatar_image VARCHAR(200) NULL
        COMMENT '头像图片访问路径，如 /uploads/avatar/xxx.png；NULL 表示用 avatar_color 兜底'
        AFTER avatar_color;

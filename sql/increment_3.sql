-- ============================================================
-- 第三批增量：视频进度防刷（记录上次上报时间）
-- ============================================================
USE yd_course;

ALTER TABLE t_video_progress
    ADD COLUMN last_report_at DATETIME NULL COMMENT '上次上报时间（防刷速率校验用）' AFTER finished;

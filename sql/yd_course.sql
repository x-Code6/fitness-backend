-- ============================================================
-- 课程服务库：yd_course（课程/门店/秒杀活动/视频/进度）
-- ============================================================
CREATE DATABASE IF NOT EXISTS yd_course DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_course;

-- 门店表（经纬度用于 Redis GEO）
CREATE TABLE IF NOT EXISTS t_store (
    id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    name       VARCHAR(50)   NOT NULL COMMENT '门店名',
    lng        DECIMAL(10,6) NOT NULL COMMENT '经度',
    lat        DECIMAL(10,6) NOT NULL COMMENT '纬度',
    address    VARCHAR(100)  NOT NULL COMMENT '地址',
    rating     DECIMAL(2,1)  NOT NULL DEFAULT 4.5 COMMENT '评分',
    tags       VARCHAR(100)  NULL COMMENT '特色标签，逗号分隔',
    open_hours VARCHAR(20)   NULL COMMENT '营业时间',
    phone      VARCHAR(20)   NULL COMMENT '电话',
    coaches    INT           NOT NULL DEFAULT 0 COMMENT '教练数',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT '门店表';

-- 课程表
CREATE TABLE IF NOT EXISTS t_course (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(50)   NOT NULL COMMENT '课程名',
    type        VARCHAR(10)   NOT NULL COMMENT '类型：团课/私教/瑜伽/骑行/操课',
    coach       VARCHAR(20)   NOT NULL COMMENT '教练',
    coach_title VARCHAR(50)   NULL COMMENT '教练资质',
    duration    INT           NOT NULL DEFAULT 60 COMMENT '时长(分钟)',
    calories    INT           NOT NULL DEFAULT 300 COMMENT '消耗(千卡)',
    capacity    INT           NOT NULL DEFAULT 20 COMMENT '容量',
    booked      INT           NOT NULL DEFAULT 0 COMMENT '已约人数',
    price       DECIMAL(10,2) NOT NULL COMMENT '原价',
    tags        VARCHAR(100)  NULL COMMENT '标签，逗号分隔',
    store_id    BIGINT        NOT NULL COMMENT '门店 id',
    store_name  VARCHAR(50)   NOT NULL COMMENT '门店名(冗余)',
    next_time   VARCHAR(20)   NOT NULL COMMENT '下次开课文案',
    description TEXT          NULL COMMENT '介绍',
    suitable    VARCHAR(100)  NULL COMMENT '适合人群',
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '1 上架 0 下架',
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_type (type)
) ENGINE = InnoDB COMMENT '课程表';

-- 秒杀活动表
CREATE TABLE IF NOT EXISTS t_seckill_activity (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    course_id    BIGINT        NOT NULL COMMENT '课程 id',
    seckill_price DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
    total        INT           NOT NULL COMMENT '总名额',
    remain       INT           NOT NULL COMMENT 'DB 剩余名额',
    start_time   DATETIME      NOT NULL COMMENT '开始时间',
    end_time     DATETIME      NOT NULL COMMENT '结束时间',
    status       TINYINT       NOT NULL DEFAULT 0 COMMENT '0 待开始 1 进行中 2 已结束',
    PRIMARY KEY (id),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT '秒杀活动表';

-- 跟练视频表
CREATE TABLE IF NOT EXISTS t_video (
    id       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title    VARCHAR(50)  NOT NULL COMMENT '标题',
    coach    VARCHAR(20)  NOT NULL COMMENT '教练',
    duration INT          NOT NULL COMMENT '时长(分钟)',
    level    VARCHAR(10)  NOT NULL COMMENT '难度',
    calories INT          NOT NULL COMMENT '消耗(千卡)',
    plays    INT          NOT NULL DEFAULT 0 COMMENT '跟练人数',
    url      VARCHAR(200) NULL COMMENT '视频地址(Mock 为空)',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT '跟练视频表';

-- 视频进度表
CREATE TABLE IF NOT EXISTS t_video_progress (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT      NOT NULL COMMENT '用户 id',
    video_id   BIGINT      NOT NULL COMMENT '视频 id',
    progress   INT         NOT NULL DEFAULT 0 COMMENT '最大进度 0~100',
    finished   TINYINT     NOT NULL DEFAULT 0 COMMENT '是否完课',
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_video (user_id, video_id)
) ENGINE = InnoDB COMMENT '视频学习进度表';

-- ---------- 种子数据 ----------
INSERT INTO t_store (id, name, lng, lat, address, rating, tags, open_hours, phone, coaches) VALUES
(1, '悦动·大学城店', 113.676500, 34.730500, '大学城风情街 3 层 301', 4.9, '团课多,停车方便', '07:00-23:00', '0371-88886666', 12),
(2, '悦动·万达店',   113.652000, 34.753000, '万达广场 B 座 5 层',    4.8, '瑜伽馆,有泳池',  '07:00-22:30', '0371-88887777', 8),
(3, '悦动·高新店',   113.598000, 34.769000, '高新区创新大厦 2 层',   4.7, '私教强,24h',    '24 小时',     '0371-88888888', 15),
(4, '悦动·CBD 店',   113.725000, 34.762000, 'CBD 中央公园东侧',      4.6, '器械全',        '08:00-22:00', '0371-88889999', 10);

INSERT INTO t_course (id, name, type, coach, coach_title, duration, calories, capacity, booked, price, tags, store_id, store_name, next_time, description, suitable, status) VALUES
(1, '燃脂跳操·Zumba 热舞', '操课', 'Lily', 'AES 认证教练', 60, 520, 20, 17, 68.00, '燃脂,新手友好', 1, '悦动·大学城店', '今天 19:00', '跟随节奏感强烈的音乐完成全身燃脂训练，零基础也能快速上手。', '想减脂、喜欢音乐节奏的会员', 1),
(2, '杠铃塑形·BodyPump',   '团课', 'Kevin', 'NSCA-CPT', 45, 430, 16, 16, 78.00, '塑形,高人气', 1, '悦动·大学城店', '今天 20:30', '使用轻重量杠铃完成全身肌群耐力训练，紧致线条。', '想塑形提线条的会员', 1),
(3, '晨间流瑜伽',          '瑜伽', '苏苏', 'RYT200 认证', 60, 260, 12, 7, 58.00, '拉伸,减压', 2, '悦动·万达店', '明天 07:30', '呼吸与体式结合的流动练习，唤醒身体。', '久坐党、瑜伽初学者', 1),
(4, '动感单车·极速卡路里', '骑行', '大鹏', '精英骑行教练', 45, 600, 24, 15, 88.00, '爆汗,高强', 3, '悦动·高新店', '今天 18:30', '在灯光与音乐中完成高强度间歇骑行。', '追求高效燃脂的会员', 1),
(5, '私教·体态矫正 1v1',   '私教', 'Alan', '运动康复方向', 60, 350, 1, 0, 328.00, '1v1,康复', 3, '悦动·高新店', '预约制', '针对圆肩驼背等体态问题定制训练方案。', '有体态困扰的会员', 1),
(6, '搏击操·释放压力',     '操课', 'Lily', 'AES 认证教练', 50, 550, 20, 9, 68.00, '解压,燃脂', 1, '悦动·大学城店', '后天 19:00', '拳击基础动作 + 有氧编排，痛快出拳。', '压力大、想发泄的会员', 1),
(7, '阴瑜伽·深度放松',     '瑜伽', '苏苏', 'RYT200 认证', 75, 180, 12, 5, 58.00, '放松,睡前', 2, '悦动·万达店', '今天 21:00', '长时间静态伸展，改善睡眠质量。', '睡眠质量差的会员', 1),
(8, '核心强化·腹肌撕裂者', '团课', 'Kevin', 'NSCA-CPT', 30, 320, 16, 11, 48.00, '核心,短时高效', 1, '悦动·大学城店', '明天 12:30', '30 分钟集中轰炸腹部核心。', '想强化核心的会员', 1),
(9, '私教·增肌入门 1v1',   '私教', '大鹏', '精英力量教练', 60, 400, 1, 0, 298.00, '1v1,增肌', 3, '悦动·高新店', '预约制', '从三大项动作模式学起。', '零基础增肌人群', 1),
(10, '轻燃普拉提',         '团课', '苏苏', '普拉提认证', 50, 280, 10, 6, 72.00, '体态,女性友好', 2, '悦动·万达店', '明天 10:00', '垫上普拉提，激活深层核心。', '产后恢复、体态改善人群', 1);

-- 秒杀活动：开始时间在昨天（定时任务 30 秒内会扫到并预热进 Redis），两场抢购中 + 一场已售罄
INSERT INTO t_seckill_activity (course_id, seckill_price, total, remain, start_time, end_time, status) VALUES
(4, 19.90, 30, 8,  NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 3 DAY,  0),
(1, 29.90, 30, 15, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 5 DAY,  0),
(2, 39.90, 20, 0,  NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 8 DAY,  0);

INSERT INTO t_video (id, title, coach, duration, level, calories, plays, url) VALUES
(1, '10 分钟晨间唤醒操',   '苏苏', 10, '入门', 80, 12000, NULL),
(2, '20 分钟全身燃脂 HIIT', '大鹏', 20, '进阶', 260, 8700, NULL),
(3, '15 分钟腹部核心训练',  'Kevin', 15, '中级', 160, 6300, NULL),
(4, '30 分钟低冲击有氧',    'Lily', 30, '入门', 220, 5100, NULL),
(5, '12 分钟肩颈放松',      '苏苏', 12, '入门', 60, 9900, NULL),
(6, '25 分钟下肢翘臀塑造',  'Kevin', 25, '进阶', 240, 4400, NULL);

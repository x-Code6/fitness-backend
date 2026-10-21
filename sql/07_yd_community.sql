-- ============================================================
-- 悦动健身 v2 · 社区域 yd_community
-- 归属服务：fitness-community (8105)
-- 职责：动态（三档可见性）、点赞、收藏、两级评论、关注关系
--
-- ★这个库是 2026-10-14 重新启用的（docs 4.12，推翻 D20"本轮下线社区"）。
--   为什么单独一个库：社区是"高频写 + 可容忍短暂不一致 + 挂了不该拖累交易"的内容域，
--   和订单/课程同库会让内容读把交易库的缓冲池挤掉，也让分库备份的策略没法分开定。
--
-- ★无外键、跨库只存 id + 必要快照，这条全局约定在这里最吃紧：
--   动态的作者昵称、头像色、评论的昵称都是<b>发布时快照</b>，
--   读 Feed 一次都不回查用户服务（社交产品的惯例：改昵称后旧动态保持历史昵称）。
--
-- 本文件按 MySQL 容器字母序执行，所以前缀是 07：
--   01_yd_user → 02_yd_course → 03_yd_order → 04_yd_point → 05_yd_behavior → 06_yd_admin → 07
--   （t_follow 的建表语句也在本文件末尾，和旧库的字段一致，只是加了索引）
-- ============================================================

CREATE DATABASE IF NOT EXISTS yd_community
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_community;

-- ----------------------------------------------------------------
-- 动态表
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_post (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id       BIGINT       NOT NULL COMMENT '作者（跨库 → yd_user.t_user.id）',
    user_name     VARCHAR(20)  NOT NULL COMMENT '作者昵称<b>快照</b>：读 Feed 不回查用户服务，且改昵称后历史动态保持当时昵称',
    avatar_color  VARCHAR(2)   NOT NULL DEFAULT '0' COMMENT '头像配色下标快照',
    content       VARCHAR(500) NOT NULL COMMENT '正文。上限 500 字由入参 @Size 拦，这里只作兜底',
    images        VARCHAR(1000) NULL    COMMENT '配图访问路径 csv，如 /uploads/post/a.png,/uploads/post/b.png（最多 9 张）',
    topic         VARCHAR(20)  NULL     COMMENT '话题，如"打卡日常"',
    -- ★三档可见性。存一列就够，但"仅好友"的<b>查询策略必须和它分开设计</b>（docs 4.12 K3）：
    --   推荐流与关注流扇出只装 PUBLIC；FRIEND 走拉模式在"好友动态"里查；PRIVATE 只有作者自己看得到。
    --   如果把它塞进公共列表，查询会变成 visibility=PUBLIC OR (FRIEND AND author IN (互关)) OR author=我，
    --   这种 OR + IN 在 MySQL 里基本用不上索引。
    visibility    VARCHAR(10)  NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC 公开 / FRIEND 仅互关好友 / PRIVATE 私密',
    like_count    INT          NOT NULL DEFAULT 0 COMMENT '点赞数（冗余计数列，只由本服务的原子 UPDATE 改）',
    collect_count INT          NOT NULL DEFAULT 0 COMMENT '收藏数。★和点赞分列：两者语义不同（收藏=我要留着），且参与热度加权更高',
    comment_count INT          NOT NULL DEFAULT 0 COMMENT '评论数（含二级回复）',
    view_count    INT          NOT NULL DEFAULT 0 COMMENT '浏览量<b>账本</b>。★真实时累加在 Redis Hash（post:view:delta），由定时任务原子取出后 view_count = view_count + delta 刷进来；这里永远是真相，Redis 只是缓冲',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 / 0 作者删除 / 2 管理员隐藏（用户端查询只放行 1，2 可在后台恢复）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间。★同时是时间线游标与热度公式的时间因子',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 公开"最新"流与推荐流的过滤：等值(visibility,status)在前、范围(created_at)在后
    KEY idx_vis_status_time (visibility, status, created_at),
    -- 作者主页 / 我的动态 / 取关后清理
    KEY idx_user_status (user_id, status, id),
    -- ★热度排序的兜底：一期分数是算出来的（不能建表达式索引），
    --   但"按小时桶筛 + 取前 N 条"要能靠 (status, created_at) 缩小扫描窗口，
    --   所以这里只保留近 N 天参与推荐，不做全表算分
    KEY idx_status_time (status, created_at)
) ENGINE = InnoDB COMMENT '动态表';

-- ----------------------------------------------------------------
-- 点赞（一人一赞，可取消）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_post_like (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    post_id    BIGINT   NOT NULL COMMENT '哪条动态',
    user_id    BIGINT   NOT NULL COMMENT '谁点的',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- ★唯一索引是"重复点赞"的最终防线：并发双击时第二次插入撞键，
    --   应用层捕获 DuplicateKeyException 按"已点赞"返回。Redis 集合只做加速，不做真相
    UNIQUE KEY uk_post_user (post_id, user_id),
    -- "我赞过哪些"列表（我的-点赞 tab）。★没有它就只能全表扫
    KEY idx_user_time (user_id, id)
) ENGINE = InnoDB COMMENT '动态点赞';

-- ----------------------------------------------------------------
-- 收藏（一人一藏，可取消）
-- ★为什么不和点赞合成一张带 type 的表：唯一键语义（合并后必须把 type 加进唯一键，
--   否则"点过赞就收藏不了"）、计数列回退、取消动作、"我的"里两个 tab ——
--   省一张表换来四处复杂，不值
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_post_collect (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    post_id    BIGINT   NOT NULL COMMENT '哪条动态',
    user_id    BIGINT   NOT NULL COMMENT '谁收藏的',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_post_user_collect (post_id, user_id),
    KEY idx_user_time (user_id, id)
) ENGINE = InnoDB COMMENT '动态收藏';

-- ----------------------------------------------------------------
-- 评论（两级封顶）
-- ★root_id 是"只有一级"能便宜下来的关键：一级的 root_id=0、二级的 root_id=所属一级 id。
--   没有它，"展开某个一级评论下的 100 条回复"要么递归查询、要么按 parent_id 逐层捞；
--   有了它就是一条 root_id=? 的平铺查询。
-- ★故意不做无限树：递归查询难写难测，而且手机上缩进超过两层根本不可读。
-- ★不给评论加点赞（连字段都不留）：加了就是又一列计数 + 又一张关系表 + 又一套幂等。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_post_comment (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    post_id       BIGINT       NOT NULL COMMENT '属于哪条动态（同库 → t_post.id）',
    root_id       BIGINT       NOT NULL DEFAULT 0 COMMENT '★所属一级评论 id；自己就是一级时为 0。二级列表靠它一次查完',
    parent_id     BIGINT       NULL     COMMENT '直接回复的评论 id（一级为 NULL）。★只用于渲染"回复 @某人"，不参与分组',
    user_id       BIGINT       NOT NULL COMMENT '评论人（跨库 → yd_user）。<b>不匿名</b>：前端要能点进他的主页',
    user_name     VARCHAR(20)  NOT NULL COMMENT '昵称快照（同 t_post.user_name 的理由）',
    avatar_color  VARCHAR(2)   NOT NULL DEFAULT '0' COMMENT '头像配色快照',
    content       VARCHAR(200) NOT NULL COMMENT '评论内容，上限 200 字',
    reply_to_user VARCHAR(20)  NULL     COMMENT '被回复人昵称快照，仅用于"回复 @张三：……"这一行',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 / 0 已删除（本人可删。软删后一级保留占位，回复仍挂得上，否则删一条把整串带走）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 一级评论分页：等值(post_id, root_id=0) + id 游标。
    -- ★status 不在索引里：列表要<b>保留软删占位</b>（删掉的一级下面还挂着别人的回复，
    --   过滤掉会让那串回复无处可挂），所以查询不按 status 过滤；只有"回复数统计"按 status。
    KEY idx_post_root (post_id, root_id, id),
    -- 某个一级下的二级分页 + 整页回复数 GROUP BY
    KEY idx_root_time (root_id, id),
    KEY idx_user (user_id, id)
) ENGINE = InnoDB COMMENT '动态评论（两级）';

-- ----------------------------------------------------------------
-- 关注关系（单向。"好友"= 互关，用两个 EXISTS 查，不另建好友表）
-- ★为什么不建 t_friend：建了就要处理"关注/取关时双向同步"的一致性问题，
--   同步失败就出现"我显示他是好友、他不显示"。用两张索引各查一次是只读的、天然幂等
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_follow (
    id           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    -- ★列名跟实体一致用 user_id（关注人/粉丝），不是 follower_id：
    --   实体里是 @TableField("user_id")，两边不同源就是"启动不报错、一查就报 Unknown column"
    user_id      BIGINT   NOT NULL COMMENT '关注人（粉丝）',
    followee_id  BIGINT   NOT NULL COMMENT '被关注者',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 一人对一人只能关注一次；取消=物理删（关注关系没有历史价值，留软删只是垃圾行）
    UNIQUE KEY uk_follow_pair (user_id, followee_id),
    -- ★"我关注了谁"由上面的唯一键顶住，"谁关注了我"（算粉丝、算互关）必须单独一条索引：
    --   唯一键的最左前缀只顶一头
    KEY idx_followee (followee_id, user_id)
) ENGINE = InnoDB COMMENT '关注关系';

-- ============================================================
-- 种子数据（演示用，覆盖三档可见性与两级评论）
-- 用户 10001/10002 由 user 服务启动时创建（散列不写在本脚本里，见 01_yd_user.sql 末尾说明）；
-- 10003~10005 只作为快照昵称出现，不需要真实账号存在——动态/评论读的是快照列。
--
-- ⚠️ 计数列（like_count / collect_count / comment_count / view_count）是<b>演示用的"历史累计值"</b>，
--   故意比下面真实种的关系行数大得多：热度公式、"1.2k浏览""31赞"这些卡片表现都靠它。
--   所以"点赞数 12 但 t_post_like 只有 2 行"不是 bug，是这份演示数据的口径。
--   真实项目里这些列只由服务的原子 UPDATE 改，永远等于关系行数。
--   唯一一处对得上的口径：t_post_comment 的 root_id / parent_id 结构是真的，
--   一级评论 root_id=0、回复 root_id=所属一级 id（前端两级折叠要按它渲染）。
-- ============================================================

INSERT INTO t_post (id, user_id, user_name, avatar_color, content, topic, visibility, like_count, collect_count, comment_count, view_count, status, created_at) VALUES
(1, 10001, '久坐小张', '2', '第一次跟练完整做完 21 天里的第 3 天，宿舍没垫子铺毛巾做的，膝盖真的没那么响了。', '打卡日常', 'PUBLIC',  12, 3, 2, 268, 1, NOW() - INTERVAL 3 HOUR),
(2, 10002, '阿泽教练', '1', '体态矫正最常见的三个问题：圆肩、骨盆前倾、膝内扣。自己对着镜子拍一张站姿照就能看出第一个。', '教练说',   'PUBLIC',  31, 9, 1, 1024, 1, NOW() - INTERVAL 8 HOUR),
(3, 10001, '久坐小张', '2', '今天先去高新店上了节私教，下次想约早八的时段，有人一起吗？',                     '约练',     'FRIEND',  4,  0, 1,  56, 1, NOW() - INTERVAL 26 HOUR),
(4, 10004, '要考研的鹿', '0', '记录一下体重变化，只给我自己看。',                                       '日常',     'PRIVATE', 0,  0, 0,  12, 1, NOW() - INTERVAL 2 DAY),
(5, 10005, '颈椎病人', '3', '第十二分钟那个转头动作我这边咔咔响，正常吗？有懂的教练说一下。',                 '提问',     'PUBLIC',  6,  1, 2, 173, 1, NOW() - INTERVAL 5 DAY);

INSERT INTO t_post_like (post_id, user_id) VALUES (1, 10002), (1, 10004), (2, 10001), (2, 10004), (5, 10001);
INSERT INTO t_post_collect (post_id, user_id) VALUES (2, 10001), (1, 10004);

INSERT INTO t_post_comment (id, post_id, root_id, parent_id, user_id, user_name, avatar_color, content, reply_to_user, status, created_at) VALUES
(1, 1, 0, NULL, 10002, '阿泽教练',   '1', '第一天放慢做也行，先把幅度做标准，比追求次数有用。',           NULL,        1, NOW() - INTERVAL 160 MINUTE),
(2, 1, 1, 1,    10001, '久坐小张',   '2', '收到，我明天试试少做一半次数。',                             '阿泽教练',   1, NOW() - INTERVAL 150 MINUTE),
(3, 2, 0, NULL, 10001, '久坐小张',   '2', '第一点太真实了，我同事说我肩是圆的。',                       NULL,        1, NOW() - INTERVAL 6 HOUR),
(4, 5, 0, NULL, 10005, ' Alan ',     '4', '响但不变形一般没事，做完还疼就要停，我私信你几个替代动作。',   NULL,        1, NOW() - INTERVAL 4 DAY),
(5, 5, 4, 4,    10004, '要考研的鹿', '0', '我也响！跟着改了落地方式之后就没了。',                        'Alan',       1, NOW() - INTERVAL 3 DAY);

-- 关注：10001 与 10002 互关（→ 彼此是"好友"，动态 3 的 FRIEND 对他俩可见）
INSERT INTO t_follow (user_id, followee_id) VALUES
(10001, 10002), (10002, 10001), (10001, 10004), (10004, 10001), (10005, 10002);

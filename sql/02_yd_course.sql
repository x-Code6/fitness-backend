-- ============================================================
-- 悦动健身 v2 · 课程域 yd_course
-- 归属服务：fitness-course (8102)
-- 职责：门店、课程（可售/展示两类）、课程×门店可售窗口、教练与会教范围、
--       秒杀活动、跟练视频与进度、展示课评论、优惠券
--
-- v2 相对旧库的三处大改：
--   ① 删 t_course_schedule（场次）——私教形态下"时刻"来自教练空闲时段，不在课程库
--   ② 删 t_course 的 capacity/booked/store_id/coach/next_time——见 docs 第四部分 4.1.1
--   ③ 门店评分不再放本库（评价在 yd_order，汇总统计同库才不需要分布式事务）
-- ============================================================

CREATE DATABASE IF NOT EXISTS yd_course
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_course;

-- ----------------------------------------------------------------
-- 门店表
-- 注意：open_start_hour/open_end_hour 是【给程序算的】，open_hours_text 是【给人看的】。
-- 旧库只有一个 '07:00-23:00' 文案列，机器读不了，导致"可约时段"没法计算。
-- 展示列与计算列必须分开，别拿展示字段做业务判断。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_store (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    name            VARCHAR(50)   NOT NULL COMMENT '门店名',
    lng             DECIMAL(10,6) NOT NULL COMMENT '经度。用 DECIMAL 不用 DOUBLE：浮点有精度误差，6 位小数约 0.1 米',
    lat             DECIMAL(10,6) NOT NULL COMMENT '纬度',
    address         VARCHAR(100)  NOT NULL COMMENT '详细地址，前端直接展示',
    intro           VARCHAR(200)  NULL     COMMENT '门店简介',
    tags            VARCHAR(100)  NULL     COMMENT '特色标签，逗号分隔（仅展示，检索靠 LIKE；真实项目应拆标签表或上 ES）',
    image           VARCHAR(200)  NULL     COMMENT '门店图片相对路径 /uploads/xxx.png，由管理端上传',
    open_start_hour INT           NOT NULL DEFAULT 7  COMMENT '营业开始整点（0~23），教练可约时段的下边界',
    open_end_hour   INT           NOT NULL DEFAULT 23 COMMENT '营业结束整点（0~24），可约时段的上边界',
    is_24h          TINYINT       NOT NULL DEFAULT 0  COMMENT '1 全天营业，此时忽略上面两个整点列，可约区间取 0~24',
    open_hours_text VARCHAR(20)   NULL     COMMENT '营业时间展示文案，如"07:00-23:00""24 小时"（只给用户看，不参与计算）',
    phone           VARCHAR(20)   NULL     COMMENT '联系电话',
    status          TINYINT       NOT NULL DEFAULT 1  COMMENT '1 营业中 / 0 已关闭',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT '门店表（坐标同时用于 Redis GEO：store:geo）';

-- ----------------------------------------------------------------
-- 课程表（v2：课程是"可购买的商品/内容"，不再自带时刻与座位）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_course (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    name          VARCHAR(50)   NOT NULL COMMENT '课程名',
    -- ★ v2 的核心分叉位：两类课程共用一张表（共用字段占九成），差异只体现在
    --   "能不能买"和"要不要约教练"，用这一个位判断足够，不必拆两张表各写一套 CRUD。
    course_kind   VARCHAR(10)   NOT NULL DEFAULT 'SALE' COMMENT 'SALE 可购买可约教练的线下课 / CONTENT 展示型课程（看视频+评论+回复，无需购买）',
    type          VARCHAR(10)   NOT NULL COMMENT '分类标签：燃脂/力量/拉伸/心肺/体态/康复。v2 里它只是分类，不再有"团课/私教"语义（团课已取消）',
    lesson_hours  INT           NOT NULL DEFAULT 1 COMMENT '★一节课占几个小时格子，只允许 1/2/3。预约以"节"为最小单位不可拆分——这个约束让时段冲突从"区间重叠判断"降级为"格子是否被占"',
    duration      INT           NOT NULL DEFAULT 60 COMMENT '课程净时长（分钟），仅展示用（45 分钟的课仍占 1 个整点格子）',
    calories      INT           NOT NULL DEFAULT 300 COMMENT '预计消耗千卡',
    price         DECIMAL(10,2) NOT NULL COMMENT '原价（单节/单次价）。CONTENT 课程填 0.00',
    sale_type     VARCHAR(10)   NULL COMMENT '仅 SALE 有值：TIMES 按次（买一单给固定次数）/ UNLIMITED（有效期内不限次）。CONTENT 为 NULL',
    times_per_buy INT           NULL COMMENT '仅 sale_type=TIMES 有值：买一单给几次，默认 1；UNLIMITED 为 NULL',
    tags          VARCHAR(100)  NULL COMMENT '标签逗号分隔，如"燃脂,新手友好"',
    cover_image   VARCHAR(200)  NULL COMMENT '封面图相对路径；NULL 时前端回退用 cover_color 渐变',
    cover_color   VARCHAR(2)    NOT NULL DEFAULT '0' COMMENT '封面渐变色下标 0~5（无图方案的兜底）',
    description   TEXT          NULL COMMENT '课程介绍（详情长文）',
    suitable      VARCHAR(100)  NULL COMMENT '适合人群',
    status        TINYINT       NOT NULL DEFAULT 1 COMMENT '1 上架 / 0 下架。下架不进列表，详情缓存重建时会清缓存并写空值标记',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_type (type),
    -- 列表页最主线的查询：按"类别 + 上架"筛。kind 在前是因为前端首页/课程页都是先定类别
    KEY idx_kind_status (course_kind, status, type)
) ENGINE = InnoDB COMMENT '课程表（可售课 + 展示课）';

-- ----------------------------------------------------------------
-- 课程 × 门店：谁在卖这门课，以及两层时间
-- ★两层时间绝不能合并成一列，它们校验的动作和时机不同：
--   ① 可购买窗口 sell_start/sell_end → 加购物车、创建订单时校验，不过 = "该课程已停售"
--   ② 可使用窗口 valid_* → 只用来【算出】t_entitlement.valid_until，之后预约时校验，
--      不过 = "凭证已过期"
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_course_store (
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    course_id       BIGINT      NOT NULL COMMENT '课程（逻辑外键 → t_course.id，同库可 JOIN）',
    store_id        BIGINT      NOT NULL COMMENT '在哪家店卖/上（逻辑外键 → t_store.id）',
    sell_start_time DATETIME    NOT NULL COMMENT '① 开售时间（含）',
    sell_end_time   DATETIME    NULL     COMMENT '① 停售时间（含）；NULL = 长期在售（对应常年日常价课）',
    valid_type      VARCHAR(10) NOT NULL DEFAULT 'DAYS' COMMENT '② FOREVER 永不过期 / DAYS 自支付时刻起 N 天 / RANGE 固定起止窗口。三种由类型决定读哪组列，不做"两者都填取交集"的隐式规则',
    valid_days      INT         NULL     COMMENT 'valid_type=DAYS 时生效：领后 N 天内可用',
    use_start_time  DATETIME    NULL     COMMENT 'valid_type=RANGE 时生效：可使用起始',
    use_end_time    DATETIME    NULL     COMMENT 'valid_type=RANGE 时生效：可使用截止',
    stock           INT         NULL     COMMENT '可售份数；NULL = 不限份。旧库的 capacity/booked 座位语义已废弃',
    status          TINYINT     NOT NULL DEFAULT 1 COMMENT '1 在售 / 0 下架（同课不同店可分别下架）',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_store (course_id, store_id),
    -- 门店详情页/首页"本店课程列表"的查询：本店 + 在售 + 未到停售时间。
    -- 等值列(store_id,status)在前、范围列(sell_end_time)在后，索引才能同时完成过滤和范围扫
    KEY idx_store_sell (store_id, status, sell_end_time)
) ENGINE = InnoDB COMMENT '课程-门店可售关系与两层时间窗';

-- ----------------------------------------------------------------
-- 教练表
-- v2 里教练是【可预约的物理资源】，一个教练同一时段只服务一个人（团课已取消）。
-- work_start_hour/work_end_hour/work_days 三列是"取消教练排班"换来的替代品：
-- 不预生成时段行，只描述原则上几点到几点、周几能约，可约时刻查询时实时算。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_coach (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    store_id        BIGINT       NOT NULL COMMENT '隶属门店（逻辑外键 → t_store.id）',
    name            VARCHAR(20)  NOT NULL COMMENT '教练姓名',
    title           VARCHAR(50)  NULL     COMMENT '资质文案，如 NSCA-CPT / RYT200',
    intro           VARCHAR(200) NULL     COMMENT '简介，详情页展示',
    phone           VARCHAR(20)  NULL     COMMENT '联系电话（管理端可见，C 端 VO 不给这个字段）',
    avatar_image    VARCHAR(200) NULL     COMMENT '教练照片相对路径 /uploads/xxx.png',
    avatar_color    VARCHAR(2)   NOT NULL DEFAULT '0' COMMENT '无照片时的头像配色下标 0~5',
    gender          TINYINT      NULL     COMMENT '1 男 / 2 女 / NULL 未填。仅作筛选，不做强制',
    work_start_hour INT          NOT NULL DEFAULT 9  COMMENT '每日可约起始整点（0~23）',
    work_end_hour   INT          NOT NULL DEFAULT 21 COMMENT '每日可约结束整点（1~24）',
    work_days       VARCHAR(20)  NOT NULL DEFAULT '1,2,3,4,5,6,7' COMMENT '可约星期，逗号分隔，1=周一。当天不在其中则该教练全天不可约',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1 可约 / 0 停用（停用不进可约教练列表）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 防同店重名；最左前缀顺带服务"按门店查教练"，所以不用再单独建 store_id 索引
    UNIQUE KEY uk_store_name (store_id, name),
    KEY idx_store_status (store_id, status)
) ENGINE = InnoDB COMMENT '教练表';

-- ----------------------------------------------------------------
-- 教练 × 会教什么课（多对多）
-- 管理端配置时课程只能从"已存在的课程"里选（下拉，禁止手输课名）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_coach_course (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    coach_id   BIGINT   NOT NULL COMMENT '教练（逻辑外键 → t_coach.id）',
    course_id  BIGINT   NOT NULL COMMENT '课程（逻辑外键 → t_course.id）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- ①防重复配课 ②最左前缀服务管理端"这个教练会教哪些课"
    UNIQUE KEY uk_coach_course (coach_id, course_id),
    -- ③★C 端最核心的查询："这门课在本店有哪些教练可约"。
    --   一个唯一键只顶一头，另一头必须单独建索引——和 t_follow 那对索引同理。
    KEY idx_course (course_id)
) ENGINE = InnoDB COMMENT '教练可授课范围';

-- ----------------------------------------------------------------
-- 秒杀活动表（v2：秒杀卖的是【权益/份数】，与教练有多少空闲时段完全解耦）
-- 这解决了旧模型里"秒杀单绕过场次容量、book_time 是'预约制'文案"的语义混乱。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_seckill_activity (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    course_id     BIGINT        NOT NULL COMMENT '秒杀哪门课（只能选 course_kind=SALE 的课）',
    store_id      BIGINT        NOT NULL COMMENT '★哪家店的专场。这一列是实现时才补上的：秒杀建单要写 store_id（凭证认店，之后只能在该店约教练），而"店"该由活动定义而不是让用户抢的时候再选——否则抢到的价格对应哪家店根本说不清',
    seckill_price DECIMAL(10,2) NOT NULL COMMENT '秒杀价，与 t_course.price 并存 → 订单要展示划线原价',
    total         INT           NOT NULL COMMENT '总名额=可卖出多少份权益（不是座位数）',
    remain        INT           NOT NULL COMMENT 'DB 侧剩余份数（账本）。Redis 侧 seckill:stock 是抢的依据，两份并存见 docs 第一部分讲解⑧',
    start_time    DATETIME      NOT NULL COMMENT '开抢时间',
    end_time      DATETIME      NOT NULL COMMENT '结束时间',
    status        TINYINT       NOT NULL DEFAULT 0 COMMENT '0 待开始 / 1 进行中 / 2 已结束。由定时任务扫描推进，推进前先预热库存',
    preheat_at    DATETIME      NULL     COMMENT '★预热完成时间。发放动作靠 UPDATE...WHERE status=0 的 CAS 保证只有一个实例/一轮成功，这一列便于排查重复发放',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 定时任务每 30 分钟扫"待开始 + 进行中"，靠这个索引
    KEY idx_status_start (status, start_time),
    KEY idx_course (course_id),
    -- 「这家店当前有哪些秒杀专场」：秒杀页按门店筛选走这条
    KEY idx_store_status (store_id, status)
) ENGINE = InnoDB COMMENT '秒杀活动表';

-- ----------------------------------------------------------------
-- 跟练视频表
-- v2 修掉两个旧问题：①t_video 曾是孤立表（无任何关联字段）→ 加 course_id
--                    ②url 在 VideoVO 里没透出 → 前端播放器只能假。现在 url 必须进 VO
-- 视频不做上传，直接用网上 http 链接（管理端填地址）。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_video (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    course_id    BIGINT       NULL     COMMENT '属于哪门展示课程（→ t_course.id，同库可 JOIN 取课名）；NULL = 首页跟练专区的独立视频',
    coach_id     BIGINT       NULL     COMMENT '出镜教练（→ t_coach.id，同库可 JOIN，所以不存名字快照）',
    title        VARCHAR(50)  NOT NULL COMMENT '视频标题',
    intro        VARCHAR(200) NULL     COMMENT '简介',
    level        VARCHAR(10)  NOT NULL COMMENT '难度：入门/中级/进阶',
    duration     INT          NOT NULL COMMENT '时长（分钟），前端进度防刷要按它换算',
    calories     INT          NOT NULL DEFAULT 0 COMMENT '预计消耗千卡',
    plays        INT          NOT NULL DEFAULT 0 COMMENT '跟练人数（计数列，只增不减，非精确统计）',
    url          VARCHAR(300) NULL     COMMENT '★播放地址，直接存外部 http 链接。管理端可填，VO 必须透出这个字段',
    cover_image  VARCHAR(200) NULL     COMMENT '视频封面相对路径或外链',
    sort_no      INT          NOT NULL DEFAULT 0 COMMENT '课程内排序号',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1 上架 / 0 下架',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_course (course_id, sort_no)
) ENGINE = InnoDB COMMENT '跟练视频表';

-- ----------------------------------------------------------------
-- 视频学习进度（"用户 × 视频"的多对多关联表，一行一个人一个视频）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_video_progress (
    id              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT   NOT NULL COMMENT '谁（跨库 → yd_user.t_user.id）',
    video_id        BIGINT   NOT NULL COMMENT '哪个视频（同库 → t_video.id）',
    progress        INT      NOT NULL DEFAULT 0 COMMENT '★最大进度 0~100，语义是"看过的最大值"不是当前值。更新语句必须带 WHERE progress < newProgress，否则往回拖进度条会把记录改小、完课判定会乱',
    finished        TINYINT  NOT NULL DEFAULT 0 COMMENT '是否完课。★v1 的 bug：代码里从未把它置 1，导致完课事件反复发。v2 必须在 progress 达 100 时原子置 1',
    finished_at     DATETIME NULL     COMMENT '完课时间（新增列）。有了它，完课事件的幂等键可以用 userId:videoId 派生，不必每次生成新 UUID',
    last_report_at  DATETIME NULL     COMMENT '上次上报时间，防刷用：要求"新增进度 ≤ 经过秒数×1.5 + 15"，即不可能 10 秒看完 5 分钟视频',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- ★一人一视频一行 + 唯一索引兜底并发首报（先查再插不是原子的）
    UNIQUE KEY uk_user_video (user_id, video_id),
    KEY idx_video (video_id)
) ENGINE = InnoDB COMMENT '视频学习进度表';

-- ----------------------------------------------------------------
-- 展示课评论（与"订单评价"t_rating 是两回事，分表理由如下）
--   谁能写：评论=任何登录用户；评价=必须买过且已支付
--   有无分值：评论无；评价 1~5 星
--   统计用途：评论只做列表展示；评价要算门店/课程/教练评分
-- 准入条件、校验逻辑、统计口径全不同 → 两张表。长得像不代表是同一个东西。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_course_comment (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    course_id     BIGINT       NOT NULL COMMENT '评论哪门展示课程（同库可 JOIN t_course）',
    -- ★root_id 是"只有两级"能便宜下来的关键：一级的 root_id=0、二级的 root_id=所属一级 id。
    --   没有它，"取某个一级下面的全部回复"要么按 parent_id 一层层捞、要么递归查询；
    --   有了它就是一条 root_id=? 的平铺查询（与社区评论 t_post_comment 用同一套形状，
    --   两处不是一个表，但读代码的人只需要懂一次两级方案）
    root_id       BIGINT       NOT NULL DEFAULT 0 COMMENT '所属一级评论 id；自己就是一级时为 0',
    user_id       BIGINT       NOT NULL COMMENT '评论人（跨库 → yd_user）',
    user_name     VARCHAR(20)  NOT NULL COMMENT '昵称快照（跨库 + 高频读 + 要历史原值 → 冗余）',
    avatar_color  VARCHAR(2)   NOT NULL DEFAULT '0' COMMENT '头像配色快照',
    content       VARCHAR(300) NOT NULL COMMENT '评论内容',
    parent_id     BIGINT       NULL     COMMENT '直接回复的那条评论 id（一级为 NULL）。★只用于渲染"回复 @张三"，不参与分组',
    reply_to_user VARCHAR(20)  NULL     COMMENT '被回复人昵称，仅用于渲染"回复 @张三：说得对"这一行',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 / 0 已删除（逻辑删除，本人可删）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 取某课的一级评论：等值(course_id, root_id=0) + id 游标（★id 自增就是时间序，不必带 created_at）
    KEY idx_course_root (course_id, root_id, id),
    -- 某个一级下的回复列表
    KEY idx_root (root_id, id)
    -- ★parent_id 上<b>不建索引</b>：分组只看 root_id，parent_id 从不作为查询条件出现。
    --   给一个"逻辑上相关但没人按它查"的列建索引，等于白付写入成本
) ENGINE = InnoDB COMMENT '展示课程评论/回复（两级，靠 root_id 平铺）';

-- ----------------------------------------------------------------
-- 优惠券表（模板/活动）
-- v2 四项扩展：①区分秒杀券/普通券 ②秒杀券限库存+限领，普通券不限
--               ③"可领时间"与"可用时间"彻底分离 ④可用范围可限课程/门店
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_coupon (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    title            VARCHAR(50)   NOT NULL COMMENT '券名，如"新人无门槛券"',
    coupon_type      VARCHAR(10)   NOT NULL DEFAULT 'NORMAL' COMMENT '★SECKILL 秒杀券（限库存+限单人领取）/ NORMAL 普通券（不限库存、默认不限领）',
    threshold        DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '使用门槛：满 X 元可用，0 = 无门槛',
    discount         DECIMAL(10,2) NOT NULL COMMENT '抵扣金额（满减券，v2 不做折扣券）',
    total            INT           NULL COMMENT '发放总量；★NORMAL 可为 NULL 表示不限',
    remain           INT           NULL COMMENT '剩余可领；★NORMAL 为 NULL，SECKILL 必须非空（走 Redis Lua 原子扣减）',
    limit_per_user   INT           NOT NULL DEFAULT 0 COMMENT '每人限领张数；★0 = 不限（NORMAL 默认 0）。用 0 而不是 NULL 表达"不限"，代码里 if(limit>0 && got>=limit) 一句搞定，不用判 NULL',
    -- ③ 第一层：能不能领
    receive_start_time DATETIME    NOT NULL COMMENT '可领开始时间',
    receive_end_time   DATETIME    NOT NULL COMMENT '可领结束时间（★只管领取，不再兼职"使用截止"——旧库 end_time 一列两用是隐患）',
    -- ③ 第二层：领到以后什么时候能用
    valid_type       VARCHAR(10)   NOT NULL DEFAULT 'DAYS' COMMENT 'FOREVER 长期有效 / DAYS 领后 N 天 / RANGE 固定窗口。同 t_course_store 的语义',
    valid_days       INT           NULL     COMMENT 'valid_type=DAYS 时生效',
    use_start_time   DATETIME      NULL     COMMENT 'valid_type=RANGE 时生效：可用起始',
    use_end_time     DATETIME      NULL     COMMENT 'valid_type=RANGE 时生效：可用截止',
    -- ④ 可用范围。★必须有 scope 这个显式字段：否则只能靠"关联表为空"判断"全场通用"，
    --    将来分不清是"通用券"还是"配漏了范围"。
    scope            TINYINT       NOT NULL DEFAULT 0 COMMENT '0 全场通用 / 1 限指定课程（见 t_coupon_course）/ 2 限指定门店（见 t_coupon_store）',
    description      VARCHAR(200)  NULL     COMMENT '券说明补充文案；规则明细由后端按类型用策略生成，不手写在这里',
    status           TINYINT       NOT NULL DEFAULT 1 COMMENT '1 上架 / 0 下架 / 2 作废',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_type_status (coupon_type, status, receive_end_time)
) ENGINE = InnoDB COMMENT '优惠券模板';

-- 用户持券表（实例：某人手里的一张券）。与 t_coupon 是"模板-实例"分离。
CREATE TABLE IF NOT EXISTS t_user_coupon (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '持券记录 id（订单里 coupon_record_id 存它）',
    user_id     BIGINT      NOT NULL COMMENT '持有人',
    coupon_id   BIGINT      NOT NULL COMMENT '券模板 id',
    status      VARCHAR(10) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED 未用 / USED 已用 / EXPIRED 已过期',
    order_no    VARCHAR(32) NULL COMMENT '核销在哪张订单上（★跨库用订单号字符串关联，与 t_booking.order_no 对应）',
    received_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间（valid_days 的起算点）',
    used_at     DATETIME    NULL COMMENT '使用时间',
    PRIMARY KEY (id),
    -- ★v2 关键变更：这里【不再有 uk_user_coupon(user_id,coupon_id)】。
    --   旧库那条唯一索引在数据库层面锁死了"一人一张"，而 v2 要支持普通券不限领、
    --   秒杀券限领 N 张。限领判断改为：SECKILL 走 Redis Hash 计数（Lua 内原子判），
    --   NORMAL 走 SELECT COUNT(*)（低频操作，普通写法即可）。
    KEY idx_user_coupon (user_id, coupon_id, status)
) ENGINE = InnoDB COMMENT '用户持券表';

CREATE TABLE IF NOT EXISTS t_coupon_course (
    id        BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    coupon_id BIGINT NOT NULL COMMENT '券 id',
    course_id BIGINT NOT NULL COMMENT '该券可用于这门课（scope=1 时生效）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_course (coupon_id, course_id),
    KEY idx_course (course_id)
) ENGINE = InnoDB COMMENT '券-可用课程范围';

CREATE TABLE IF NOT EXISTS t_coupon_store (
    id        BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    coupon_id BIGINT NOT NULL COMMENT '券 id',
    store_id  BIGINT NOT NULL COMMENT '该券只在这家店可用（scope=2 时生效）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_store (coupon_id, store_id)
) ENGINE = InnoDB COMMENT '券-可用门店范围';

-- ============================================================
-- 种子数据（演示用，覆盖 v2 的每一种形态组合）
-- ============================================================

INSERT INTO t_store (id, name, lng, lat, address, intro, tags, open_start_hour, open_end_hour, is_24h, open_hours_text, phone, status) VALUES
(1, '悦动·大学城店', 113.676500, 34.730500, '大学城风情街 3 层 301', '面积最大、燃脂团改私教后的主力门店', '燃脂多,停车方便', 7,  23, 0, '07:00-23:00', '0371-88886666', 1),
(2, '悦动·万达店',   113.652000, 34.753000, '万达广场 B 座 5 层',   '以瑜伽与普拉提见长',           '瑜伽馆,有泳池',  7,  22, 0, '07:00-22:30', '0371-88887777', 1),
(3, '悦动·高新店',   113.598000, 34.769000, '高新区创新大厦 2 层', '器械与力量区最全，深夜可练',     '私教强,24h',    0,  24, 1, '24 小时',     '0371-88888888', 1),
(4, '悦动·CBD 店',   113.725000, 34.762000, 'CBD 中央公园东侧',     '写字楼客群为主，早晚高峰集中', '器械全',        8,  22, 0, '08:00-22:00', '0371-88889999', 1);

-- SALE 课程：覆盖 TIMES/UNLIMITED、1~2 小时格子、不同价格带
INSERT INTO t_course (id, name, course_kind, type, lesson_hours, duration, calories, price, sale_type, times_per_buy, tags, cover_color, description, suitable, status) VALUES
(1,  '燃脂跳操·Zumba 热舞',   'SALE',    '燃脂', 1, 60,  520, 68.00,  'TIMES',     1, '燃脂,新手友好',   '0', '跟随节奏感强烈的音乐完成全身燃脂训练，零基础也能快速上手。', '想减脂、喜欢音乐节奏的会员', 1),
(2,  '杠铃塑形·BodyPump',     'SALE',    '力量', 1, 45,  430, 78.00,  'TIMES',     1, '塑形,高人气',     '1', '使用轻重量杠铃完成全身肌群耐力训练，紧致线条。',       '想塑形提线条的会员',      1),
(3,  '晨间流瑜伽',            'SALE',    '拉伸', 1, 60,  260, 58.00,  'TIMES',     1, '拉伸,减压',       '2', '呼吸与体式结合的流动练习，唤醒身体。',                   '久坐党、瑜伽初学者',      1),
(4,  '动感单车·极速卡路里',   'SALE',    '心肺', 1, 45,  600, 88.00,  'UNLIMITED', NULL, '爆汗,高强',    '3', '在灯光与音乐中完成高强度间歇骑行，买一次长期随便练。',   '追求高效燃脂的会员',      1),
(5,  '私教·体态矫正 2 小时',  'SALE',    '康复', 2, 120, 350, 328.00, 'TIMES',     1, '2小时,康复',     '4', '针对圆肩驼背等体态问题定制训练方案，一次两小时。',       '有体态困扰的会员',        1),
(6,  '搏击操·释放压力',       'SALE',    '燃脂', 1, 50,  550, 68.00,  'TIMES',     1, '解压,燃脂',       '5', '拳击基础动作 + 有氧编排，痛快出拳。',                     '压力大、想发泄的会员',    1),
(7,  '阴瑜伽·深度放松',       'SALE',    '拉伸', 1, 75,  180, 58.00,  'TIMES',     1, '放松,睡前',       '2', '长时间静态伸展，改善睡眠质量。',                         '睡眠质量差的会员',        1),
(8,  '核心强化·腹肌撕裂者',   'SALE',    '力量', 1, 30,  320, 48.00,  'TIMES',     1, '核心,短时高效',   '1', '30 分钟集中轰炸腹部核心。',                             '想强化核心的会员',        1),
(9,  '私教·增肌入门 2 小时',  'SALE',    '力量', 2, 120, 400, 298.00, 'TIMES',     2, '2小时,买一单两次','4', '从三大项动作模式学起，一次两小时，买一单给两次。',       '零基础增肌人群',          1),
(10, '轻燃普拉提',            'SALE',    '体态', 1, 50,  280, 72.00,  'TIMES',     1, '体态,女性友好',   '0', '垫上普拉提，激活深层核心。',                             '产后恢复、体态改善人群',  1),
-- CONTENT 展示课：无需购买，看视频 + 评论，price=0、sale_type NULL
(11, '零基础居家跟练·21 天入门', 'CONTENT', '有氧', 1, 210, 900, 0.00, NULL,     NULL, '免费,入门',    '3', '不需要器械，跟着视频从第一天练到第二十一天。',             '完全零基础想开始运动的人', 1),
(12, '肩颈放松·久坐人群专题',    'CONTENT', '康复', 1, 120, 300, 0.00, NULL,     NULL, '免费,办公族',  '2', '四个动作缓解肩颈僵硬，办公室也能做。',                     '长期伏案的会员',          1),
(13, '跑步姿势纠正课',          'CONTENT', '心肺', 1, 90,  0,   0.00, NULL,     NULL, '免费,跑步',    '5', '从落地方式到步频，把跑步伤膝的动作改过来。',                 '刚开始跑步或总受伤的会员', 1);

-- 课程 × 门店：三种 valid_type 都有示例，sell_end_time 有 NULL（长期在售）
INSERT INTO t_course_store (course_id, store_id, sell_start_time, sell_end_time, valid_type, valid_days, use_start_time, use_end_time, stock, status) VALUES
(1,  1, NOW() - INTERVAL 30 DAY, NULL,                'DAYS',    30,  NULL,                 NULL,                NULL, 1),
(1,  4, NOW() - INTERVAL 30 DAY, NULL,                'DAYS',    30,  NULL,                 NULL,                NULL, 1),
(2,  1, NOW() - INTERVAL 30 DAY, NULL,                'DAYS',    30,  NULL,                 NULL,                60,   1),
(3,  2, NOW() - INTERVAL 30 DAY, NULL,                'DAYS',    30,  NULL,                 NULL,                NULL, 1),
-- UNLIMITED 的课用 FOREVER：买了就一直能约（常年日常价）
(4,  3, NOW() - INTERVAL 30 DAY, NULL,                'FOREVER', NULL, NULL,                NULL,                NULL, 1),
-- RANGE 固定窗口：只有这一段时间能练，常用于短期训练营
(5,  3, NOW() - INTERVAL 10 DAY, NOW() + INTERVAL 20 DAY, 'RANGE', NULL, NOW(), NOW() + INTERVAL 60 DAY, 20, 1),
(6,  1, NOW() - INTERVAL 30 DAY, NULL,                'DAYS',    30,  NULL,                 NULL,                NULL, 1),
(7,  2, NOW() - INTERVAL 30 DAY, NULL,                'DAYS',    30,  NULL,                 NULL,                NULL, 1),
(8,  1, NOW() - INTERVAL 30 DAY, NULL,                'DAYS',    15,  NULL,                 NULL,                NULL, 1),
(8,  3, NOW() - INTERVAL 30 DAY, NULL,                'DAYS',    15,  NULL,                 NULL,                NULL, 1),
(9,  3, NOW() - INTERVAL 10 DAY, NOW() + INTERVAL 40 DAY, 'DAYS',  60, NULL,                NULL,                30,   1),
(10, 2, NOW() - INTERVAL 30 DAY, NULL,                'DAYS',    30,  NULL,                 NULL,                NULL, 1),
(10, 4, NOW() - INTERVAL 30 DAY, NULL,                'DAYS',    30,  NULL,                 NULL,                NULL, 1);

INSERT INTO t_coach (id, store_id, name, title, intro, phone, avatar_color, gender, work_start_hour, work_end_hour, work_days, status) VALUES
(1, 1, 'Lily', 'AES 认证教练',   '带课五年，最会调动气氛，燃脂课一节不停。', '13800000001', '0', 2,  9, 21, '1,2,3,4,5,6,7', 1),
(2, 1, 'Kevin','NSCA-CPT',       '力量与核心方向，动作细节抠得很严。',         '13800000002', '1', 1, 10, 21, '1,2,3,4,5,6',   1),
(3, 2, '苏苏', 'RYT200 认证',    '流瑜伽与阴瑜伽双线，呼吸节奏是她的强项。',   '13800000003', '2', 7, 20, '1,2,3,4,5,6,7', 1),
(4, 3, '大鹏', '精英骑行/力量教练','单车房和深蹲架两头跑，强度给得很足。',      '13800000004', '1', 8, 22, '2,3,4,5,6,7',   1),
(5, 3, 'Alan', '运动康复方向',   '体态评估 + 矫正方案，久坐人群找他的最多。',   '13800000005', '1', 9, 18, '1,2,3,4,5',     1),
(6, 2, '小雨', '普拉提认证',     '垫上普拉提与产后恢复。',                     '13800000006', '2', 9, 21, '1,2,4,5,6',     1),
(7, 4, '阿泽', 'ACE-CPT',        'CBD 店主力，早课和晚课都能带。',             '13800000007', '1', 10,22, '1,2,3,4,5,6,7', 1),
(8, 4, '楠楠', '营养与体态管理', '训练加饮食一起管，两周见效那种。',           '13800000008', '2', 9, 20, '1,3,5',         1);

INSERT INTO t_coach_course (coach_id, course_id) VALUES
(1, 1), (1, 2), (1, 6), (1, 8),
(2, 2), (2, 8), (2, 9),
(3, 3), (3, 7), (3, 10),
(4, 4), (4, 9),
(5, 5),
(6, 10), (6, 3),
(7, 1), (7, 4), (7, 6),
(8, 5), (8, 10);

-- 秒杀活动：三种状态都有，专门用来验证定时发放的两条分支
INSERT INTO t_seckill_activity (id, course_id, store_id, seckill_price, total, remain, start_time, end_time, status) VALUES
-- ① 还没到点：演示"由扫描任务预热并翻状态"
(1, 4, 3, 19.90, 30, 30, NOW() + INTERVAL 3 MINUTE, NOW() + INTERVAL 3 DAY, 0),
-- ② 开始时间已过去但状态还是 0：演示你担心的那个边界——扫描时已过点，就【直接补发，不再投延迟消息】
(2, 1, 1, 29.90, 30, 30, NOW() - INTERVAL 2 HOUR,   NOW() + INTERVAL 5 DAY, 0),
-- ③ 进行中且已售罄：演示"库存为 0 时 Lua 返回 SOLD_OUT"
(3, 2, 1, 39.90, 20, 0,  NOW() - INTERVAL 3 DAY,    NOW() + INTERVAL 8 DAY, 1);

-- 视频：url 用公开测试片源，你换成自己可访问的地址即可（视频不做上传，直接存链接）
INSERT INTO t_video (id, course_id, coach_id, title, intro, level, duration, calories, plays, url, sort_no, status) VALUES
(1, 11, 1, '第 1 天·全身激活 10 分钟', '零基础第一天，把关节打开。',   '入门', 10, 80,  12000, 'https://www.w3schools.com/html/mov_bbb.mp4', 1, 1),
(2, 11, 2, '第 2 天·核心唤醒 15 分钟', '腹部发力感找出来。',           '入门', 15, 120, 9800,  'https://www.w3schools.com/html/mov_bbb.mp4', 2, 1),
(3, 12, 5, '肩颈放松 12 分钟',        '四个动作，办公室也能做。',     '入门', 12, 60,  9900,  'https://www.w3schools.com/html/mov_bbb.mp4', 1, 1),
(4, 13, 4, '跑步落地方式纠正',        '从前脚掌落地到步频控制。',     '中级', 20, 150, 5100,  'https://www.w3schools.com/html/mov_bbb.mp4', 1, 1),
(5, NULL, 3, '20 分钟全身燃脂 HIIT',  '首页跟练专区，不属于任何课程。','进阶', 20, 260, 8700,  'https://www.w3schools.com/html/mov_bbb.mp4', 0, 1),
(6, NULL, 8, '25 分钟下肢翘臀塑造',   '跟练专区第二条。',             '进阶', 25, 240, 4400,  'https://www.w3schools.com/html/mov_bbb.mp4', 0, 1);

-- 展示课评论（含一条回复，验证两级结构渲染）
-- ★root_id：一级为 0，回复填所属一级的 id；parent_id 只指向"直接回复的那一条"（渲染用）
INSERT INTO t_course_comment (id, course_id, root_id, user_id, user_name, avatar_color, content, parent_id, reply_to_user, status, created_at) VALUES
(1, 11, 0, 10001, '久坐小张', '2', '跟着第一天做完全身热，就是节奏有点快。',       NULL, NULL,         1, NOW() - INTERVAL 3 DAY),
(2, 11, 1, 10002, '阿泽教练', '1', '第一天的动作放慢做也行，先把幅度做标准。',     1,    '久坐小张',   1, NOW() - INTERVAL 3 DAY + INTERVAL 20 MINUTE),
(3, 11, 0, 10003, '要考研的鹿', '0', '已打卡第三天，宿舍里没垫子就铺毛巾做的。',   NULL, NULL,         1, NOW() - INTERVAL 1 DAY),
(4, 12, 0, 10004, '颈椎病人', '3', '第十二分钟那个转头动作我这边咔咔响，正常吗？', NULL, NULL,         1, NOW() - INTERVAL 2 DAY),
(5, 13, 0, 10005, '跑步入门', '4', '原来我一直在脚跟砸地，改过来膝盖真的不疼了。', NULL, NULL,         1, NOW() - INTERVAL 5 HOUR);

-- 优惠券：四种组合都有（秒杀不限范围/秒杀限课程/普通不限/普通限门店）
INSERT INTO t_coupon (id, title, coupon_type, threshold, discount, total, remain, limit_per_user,
                      receive_start_time, receive_end_time, valid_type, valid_days, use_start_time, use_end_time, scope, description, status) VALUES
(1, '秒杀专享立减券', 'SECKILL', 0.00,  20.00, 100, 100, 2,
   NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 30 DAY, 'DAYS', 7, NULL, NULL, 0, '秒杀专场可用，每人限领 2 张', 1),
(2, '新人无门槛券',   'NORMAL',  0.00,  10.00, NULL, NULL, 0,
   NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 365 DAY, 'DAYS', 30, NULL, NULL, 0, '不限领取，一单只能用一张', 1),
(3, '高新店私教体验券','SECKILL', 200.00, 50.00, 50, 50, 1,
   NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 15 DAY, 'RANGE', NULL, NOW(), NOW() + INTERVAL 10 DAY, 1, '只能用于两小时私教课，每人限领 1 张', 1),
(4, '大学城店通用券', 'NORMAL',  68.00, 8.00,  NULL, NULL, 0,
   NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 90 DAY, 'FOREVER', NULL, NULL, NULL, 2, '长期有效，仅限大学城店', 1);

INSERT INTO t_coupon_course (coupon_id, course_id) VALUES (3, 5), (3, 9);
INSERT INTO t_coupon_store (coupon_id, store_id) VALUES (4, 1);

-- ============================================================
-- 悦动健身 v2 · 订单域 yd_order
-- 归属服务：fitness-order (8103)
-- 职责：购买订单、权益凭证、私教预约单、教练时段占用、评价与评分统计
--
-- v2 把旧的"一张 t_booking 同时当订单和预约"拆成三条独立状态机：
--   订单轴 t_booking      —— 钱（UNPAID/PAID/CANCELED/REFUNDED）
--   凭证轴 t_entitlement  —— 权益（ACTIVE/USED_UP/EXPIRED）
--   预约轴 t_reservation  —— 时段（BOOKED/FINISHED/CANCELED）
-- "已过期"不是订单状态，"待使用"不是预约状态，谁也别往谁身上套。
--
-- ★为什么 t_coach_busy（时段占用）放在本库而不是课程库：
--   创建预约要在【同一个事务】里写"预约单 + 占用格子"，靠事务回滚代替手写补偿。
--   跨库就做不到，只能退回旧模型那种 Feign 占位 + 三处补偿的写法。
-- ============================================================

CREATE DATABASE IF NOT EXISTS yd_order
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_order;

-- ----------------------------------------------------------------
-- 购买订单表（v2：只负责"钱"，不再带任何上课时间语义）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_booking (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键（内部）',
    order_no        VARCHAR(32)   NOT NULL COMMENT '对外订单号。不用 id 对外：自增 id 会泄露业务量，且跨库关联要传出去',
    user_id         BIGINT        NOT NULL COMMENT '下单人（跨库 → yd_user.t_user.id）',
    course_id       BIGINT        NOT NULL COMMENT '买了哪门课（跨库 → yd_course）',
    course_kind     VARCHAR(10)   NOT NULL DEFAULT 'SALE' COMMENT '快照：SALE/CONTENT。秒杀与下单只允许 SALE，这一列用于事后校验和展示分流',
    store_id        BIGINT        NOT NULL COMMENT '在哪家店买的——私教课物理位置约束，之后只能在该店约教练',
    activity_id     BIGINT        NULL COMMENT '秒杀活动 id；NULL = 普通购买（v2 里"是不是秒杀单"看这一列，比旧模型靠 book_time 文案猜干净）',
    course_name     VARCHAR(50)   NOT NULL COMMENT '课程名快照（跨库 + 高频读 + 要历史原值）',
    store_name      VARCHAR(50)   NOT NULL COMMENT '门店名快照',
    lesson_hours    INT           NOT NULL DEFAULT 1 COMMENT '快照：一节几小时（订单详情要显示"2 小时/节"）',
    sale_type       VARCHAR(10)   NOT NULL DEFAULT 'TIMES' COMMENT '快照：TIMES/UNLIMITED',
    times_granted   INT           NULL COMMENT '快照：这一单给几次；UNLIMITED 为 NULL。课程后来改了售卖形态不影响已售单',
    quantity        INT           NOT NULL DEFAULT 1 COMMENT '买了几份（1 份 = 1 张凭证）。★多份不合并，一次购买 N 份生成 N 张独立凭证',
    original_amount DECIMAL(10,2) NOT NULL COMMENT '原价小计 = 单价×数量（秒杀单靠它和 pay_amount 做划线价对比）',
    discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '优惠金额（用券抵扣）',
    pay_amount      DECIMAL(10,2) NOT NULL COMMENT '实付金额 = 原价小计 - 优惠，最低 0',
    coupon_record_id BIGINT       NULL COMMENT '使用的 t_user_coupon.id（跨库；NULL = 没用券）',
    coupon_title    VARCHAR(50)   NULL COMMENT '券名快照，订单详情要显示"已用新人无门槛券 -¥10"',
    status          VARCHAR(10)   NOT NULL DEFAULT 'UNPAID' COMMENT '★订单轴：UNPAID 待支付 / PAID 已支付 / CANCELED 已取消 / REFUNDED 已退款。字符串枚举名而非数字：状态多且日志要肉眼可读',
    cancel_reason   VARCHAR(50)   NULL COMMENT '取消原因，如"超时未支付""用户主动取消"',
    refunded        TINYINT       NOT NULL DEFAULT 0 COMMENT '1 已发生退款（可能是部分退款）。★支付与退款都是写死的模拟：不调任何支付网关',
    refund_amount   DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '累计已退款金额。★达到 pay_amount 时订单才置 REFUNDED；小于时订单仍是 PAID（部分退款）',
    refund_at       DATETIME      NULL COMMENT '最近一次退款时间',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    pay_time        DATETIME      NULL COMMENT '支付时间',
    canceled_at     DATETIME      NULL COMMENT '取消时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    -- "我的订单"列表：WHERE user_id=? [AND status=?] ORDER BY id DESC
    KEY idx_user_status (user_id, status, id),
    -- 同人同课去重查询用（v1 这里是普通索引，去重靠"先查再插"，并发双击能造出两张有效单，
    -- v2 的防护补在业务层：见 docs 4.7 幂等三档里"值得防"这一档，用 @Idempotent 拦重复提交）
    KEY idx_course_user (course_id, user_id, status)
) ENGINE = InnoDB COMMENT '购买订单表';

-- ----------------------------------------------------------------
-- 权益凭证表（买到的那份"可去上课的资格"）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_entitlement (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id      BIGINT       NOT NULL COMMENT '归谁（跨库 → yd_user）',
    order_no     VARCHAR(32)  NOT NULL COMMENT '出票订单（跨库关联用字符串，沿用 t_user_coupon.order_no 惯例）',
    course_id    BIGINT       NOT NULL COMMENT '哪门课（跨库 → yd_course）',
    store_id     BIGINT       NOT NULL COMMENT '★只能在购买门店约教练——物理位置约束',
    course_name  VARCHAR(50)  NOT NULL COMMENT '课程名快照',
    store_name   VARCHAR(50)  NOT NULL COMMENT '门店名快照',
    lesson_hours INT          NOT NULL DEFAULT 1 COMMENT '快照：一节几小时',
    sale_type    VARCHAR(10)  NOT NULL DEFAULT 'TIMES' COMMENT '快照：TIMES/UNLIMITED。管理员改了课程售卖形态不能追溯改变已售凭证',
    total_times  INT          NULL COMMENT '★TIMES = 该单给的次数；UNLIMITED = NULL（不限次）。所有次数校验必须写成 (total_times IS NULL OR used_times < total_times)，漏了前半句会把不限次课当 0 次卡',
    used_times   INT          NOT NULL DEFAULT 0 COMMENT '已消费次数。扣次时机=到点未取消（定时任务），取消预约不扣',
    -- ★按张退款的三列：退款金额必须以"这张凭证成交时实际付了多少"为准。
    --   不能用原价退（用户用了券会赚），也不能用整单实付现除（订单改价后就错）。
    paid_amount  DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '出票时按份数分摊到的实付金额。例：买 2 份实付 150 → 每张 75；除不尽余数给最后一张',
    refund_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '已退款金额。一张凭证最多退一次，refunded_at 非空即已退',
    refunded_at  DATETIME     NULL COMMENT '退款时间；NULL = 未退款。★退款不新增 status，用这一列区分“过期且已退”和“过期未退”',
    valid_until  DATETIME     NULL COMMENT '使用截止；★NULL = 永不过期。过期定时任务必须写 valid_until IS NOT NULL，否则常年课会被集体误判过期退款',
    status       VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' COMMENT '★凭证轴：ACTIVE 可用 / USED_UP 次数用完 / EXPIRED 已过期',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- "我的凭证"列表 + 待使用 tab：user_id 等值 + status 等值 + 时间排序
    KEY idx_user_status (user_id, status, valid_until),
    KEY idx_order (order_no),
    -- 过期任务扫描：status 等值 + valid_until 范围
    KEY idx_expire (status, valid_until),
    -- "这门课我还有几张可用凭证"（进入课程页看已购，v2 需求第 5 条）
    KEY idx_course_store (course_id, store_id, user_id, status)
) ENGINE = InnoDB COMMENT '用户已购课程凭证（权益）';

-- ----------------------------------------------------------------
-- 私教预约单
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_reservation (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    reservation_no  VARCHAR(32)  NOT NULL COMMENT '对外预约单号（与订单号同一生成器）',
    user_id         BIGINT       NOT NULL COMMENT '谁约的',
    entitlement_id  BIGINT       NOT NULL COMMENT '用哪张凭证换的这次预约（跨表引用本库 t_entitlement）',
    order_no        VARCHAR(32)  NOT NULL COMMENT '冗余凭证所属订单号：详情页与评价校验要直接知道来自哪一单，省一次查询',
    course_id       BIGINT       NOT NULL COMMENT '约的哪门课（跨库 → yd_course）',
    store_id        BIGINT       NOT NULL COMMENT '在哪家店（跨库 → yd_course.t_store）',
    coach_id        BIGINT       NOT NULL COMMENT '约了哪位教练（跨库 → yd_course.t_coach）',
    coach_name      VARCHAR(20)  NOT NULL COMMENT '教练名快照：教练改名或离职后，历史预约单仍要显示当时的名字',
    course_name     VARCHAR(50)  NOT NULL COMMENT '课程名快照',
    store_name      VARCHAR(50)  NOT NULL COMMENT '门店名快照',
    lesson_hours    INT          NOT NULL DEFAULT 1 COMMENT '本次占用几个整点格子（=课程的 lesson_hours）',
    start_time      DATETIME     NOT NULL COMMENT '★整点起始，如 2026-10-14 18:00:00。非整点属于脏数据，唯一索引拦不住，入库前的整点校验是业务代码的责任',
    end_time        DATETIME     NOT NULL COMMENT '= start_time + lesson_hours 小时',
    status          VARCHAR(10)  NOT NULL DEFAULT 'BOOKED' COMMENT '★预约轴：BOOKED 已预约 / FINISHED 已完成（到点未取消即置此状态并扣次）/ CANCELED 已取消',
    cancel_deadline DATETIME     NOT NULL COMMENT '★= start_time - 1 小时。取消只看这一列做 CAS，Java 里不计算时间，也不依赖服务器时钟一致',
    verify_code     VARCHAR(16)  NULL COMMENT '到店核销码（10 位数字）。v2 里核销只记录到店，不决定是否扣次',
    verified_at     DATETIME     NULL COMMENT '核销时间',
    finished_at     DATETIME     NULL COMMENT '到点置 FINISHED 的时刻 = 消费时刻',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- ★派生的"待上课占用"标记：BOOKED 时为 1，其它状态为 NULL。
    --   为什么要这么绕：MySQL 没有部分唯一索引（"只约束 status='BOOKED' 的行"），
    --   但唯一索引允许多个 NULL。于是"这一张凭证最多只能有一行 BOOKED"这件事
    --   就变成一条数据库级约束——不靠代码自觉，也不怕两个请求同时读到"还没有预约"。
    --   不叫 pending_times 之类的计数字段：计数要和取消/结账/退款处处同步，
    --   漏一处就永久错下去；状态本来就在 status 里，能算出来的不要存。
    pending_flag    TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'BOOKED' THEN 1 ELSE NULL END) STORED
                    COMMENT '派生列：1=这节课还没上（占着凭证的一次额度），NULL=已取消/已上完',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_no (reservation_no),
    UNIQUE KEY uk_verify_code (verify_code),
    -- ★一张凭证同时只能挂一节待上的课（不限次课也一样，否则一个人能把教练档期包圆）
    UNIQUE KEY uk_pending_entitlement (entitlement_id, pending_flag),
    -- "我的预约"列表 / 即将上课
    KEY idx_user_status (user_id, status, start_time),
    -- ★到点扣次定时任务专用：WHERE status='BOOKED' AND start_time<=NOW()。
    --   这个任务每分钟跑一次，没这条索引就是每分钟全表扫，别嫌索引多
    KEY idx_settle (status, start_time),
    -- 一张凭证用掉了哪几次（凭证详情页展开预约记录）
    KEY idx_entitlement (entitlement_id, status),
    -- 同教练维度的查询：某教练接下来有哪些课（管理端/教练视角）
    KEY idx_coach_start (coach_id, start_time, status)
) ENGINE = InnoDB COMMENT '私教预约单';

-- ----------------------------------------------------------------
-- 教练时段占用表（v2 并发防护的核心，一行 = 一个小时格子）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_coach_busy (
    id             BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    coach_id       BIGINT   NOT NULL COMMENT '哪位教练（跨库 → yd_course.t_coach）',
    store_id       BIGINT   NOT NULL COMMENT '所在门店（便于按店统计占用率）',
    start_time     DATETIME NOT NULL COMMENT '★整点格子起点，一行占一个小时。lesson_hours=2 的课要插 2 行（18:00 与 19:00）',
    reservation_id BIGINT   NOT NULL COMMENT '属于哪张预约单',
    user_id        BIGINT   NOT NULL,
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- ★★★这套设计里最重要的一条索引。
    -- 把"同一教练同一时段不能被两人约"从【区间重叠判断】
    -- （需要 SELECT FOR UPDATE、锁等待、死锁处理）降级成【格子存在性判断】：
    -- 两人抢同一格时后插入的人必然撞这个唯一键，事务自动回滚，不需要任何锁或 Lua。
    -- 这也是"取消教练排班 + 课程时长不可拆分"两个决定换来的红利。
    UNIQUE KEY uk_coach_start (coach_id, start_time),
    -- ★同一个人同一小时只能有一节课：业务代码里的"先查我有没有课"挡不住并发，
    --   两个不同教练的格子不冲突，只有这条 (user_id, start_time) 唯一键能兜住。
    UNIQUE KEY uk_user_start (user_id, start_time),
    -- 取消预约时按预约单号一次删掉它占的所有格子
    KEY idx_reservation (reservation_id),
    -- "某教练某天被占了哪些格"（查空闲时的主查询）
    KEY idx_coach_time (coach_id, start_time)
) ENGINE = InnoDB COMMENT '教练时段占用（一行一小时，取消才删，上完课保留）';

-- ----------------------------------------------------------------
-- 评价表（一单一评、可删不可改）
-- ★为什么放 yd_order：写评价要校验"这单已支付、这单没评过"，数据全在本库 →
--   零跨库；评分汇总统计也在本库（见下面三张 stat 表），与明细同事务更新才安全。
--   如果放 yd_course，就变成"写课程库的汇总列 + 校验订单库的支付状态"，
--   那才需要分布式事务——本项目明确不引入。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_rating (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no   VARCHAR(32)  NOT NULL COMMENT '★一单一评（一个订单只能评价一次）',
    user_id    BIGINT       NOT NULL COMMENT '评价人',
    course_id  BIGINT       NOT NULL COMMENT '评的哪门课（跨库 → yd_course）',
    store_id   BIGINT       NOT NULL COMMENT '评的哪家店（跨库 → yd_course.t_store）',
    coach_id   BIGINT       NULL COMMENT '评价时顺手选的一位教练，可空。一单约过多个教练时只计这一位',
    coach_name VARCHAR(20)  NULL COMMENT '教练名快照',
    score      TINYINT      NOT NULL COMMENT '1~5 星。入参用 @Min(1) @Max(5) 校验',
    comment    VARCHAR(300) NULL COMMENT '评价内容',
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '★1 正常 / 0 已删除（逻辑删除）。这是"可删不可改"的实现关键：如果真 DELETE 掉这一行，uk_order 就不拦了，用户删掉再评一次等于变相修改。软删保留行，唯一键继续生效',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order (order_no),
    KEY idx_course (course_id, status, created_at),
    KEY idx_store (store_id, status),
    KEY idx_coach (coach_id, status)
) ENGINE = InnoDB COMMENT '订单评价（一单一评，软删）';

-- ----------------------------------------------------------------
-- 评分汇总统计表 × 3（门店 / 课程 / 教练）
-- 只存"不可重建的原始量"（分数和、条数），★贝叶斯分不落库：
--   score = (C*M + Σ) / (C + n)，C（可信样本数）和 M（全站均值）是可调参数，
--   存成列就锁死了，改参数要刷全表。所以查询时用一条 SQL 现算。
-- 展示分给算术平均 + 条数（用户要看真实口碑）；
-- 只有排序、"优选门店"标签、首页推荐用贝叶斯分（防"1 人打 5 星排第一"，
-- 也防"1 人打 1 星被踩死"这两个方向）。
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_store_rating_stat (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    store_id     BIGINT   NOT NULL COMMENT '门店 id',
    rating_sum   INT      NOT NULL DEFAULT 0 COMMENT '所有正常评价的分数之和',
    rating_count INT      NOT NULL DEFAULT 0 COMMENT '评价条数。删除评价时这两个列都要原子回退',
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_store (store_id)
) ENGINE = InnoDB COMMENT '门店评分汇总（原子增减，不做读-改-写）';

CREATE TABLE IF NOT EXISTS t_course_rating_stat (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    course_id    BIGINT   NOT NULL COMMENT '课程 id',
    rating_sum   INT      NOT NULL DEFAULT 0,
    rating_count INT      NOT NULL DEFAULT 0,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_course (course_id)
) ENGINE = InnoDB COMMENT '课程评分汇总';

CREATE TABLE IF NOT EXISTS t_coach_rating_stat (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    coach_id     BIGINT   NOT NULL COMMENT '教练 id',
    rating_sum   INT      NOT NULL DEFAULT 0,
    rating_count INT      NOT NULL DEFAULT 0,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coach (coach_id)
) ENGINE = InnoDB COMMENT '教练评分汇总';

-- ============================================================
-- 种子数据
-- 说明：演示账号（用户 13800008819 / 管理员 admin）都不在这里预置，
--       密码散列改由服务启动时用真实的 BCryptPasswordEncoder 生成，
--       避免 SQL 里的散列串与实际加密器不同源（详见 01_yd_user.sql 末尾说明）。
--       这里只给几条历史订单/评价，让"我的订单""评分""榜单"首屏不是空的。
-- ============================================================

-- 三张凭证：一张用满、一张用了一次还剩、一张长期有效未用
INSERT INTO t_booking (id, order_no, user_id, course_id, course_kind, store_id, activity_id,
                       course_name, store_name, lesson_hours, sale_type, times_granted, quantity,
                       original_amount, discount_amount, pay_amount, status, pay_time, created_at) VALUES
(1, 'B20261010100001', 10001, 1,  'SALE', 1, NULL, '燃脂跳操·Zumba 热舞',  '悦动·大学城店', 1, 'TIMES',     1, 2, 136.00, 0.00,  136.00, 'PAID', NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY),
(2, 'B20261011100002', 10001, 4,  'SALE', 3, 1,    '动感单车·极速卡路里',  '悦动·高新店',   1, 'UNLIMITED', NULL, 1, 19.90,  0.00,  19.90,  'PAID', NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY),
(3, 'B20261012100003', 10002, 9,  'SALE', 3, NULL, '私教·增肌入门 2 小时', '悦动·高新店',   2, 'TIMES',     2, 1, 298.00, 50.00, 248.00, 'PAID', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY);

INSERT INTO t_entitlement (id, user_id, order_no, course_id, store_id, course_name, store_name,
                           lesson_hours, sale_type, total_times, used_times, paid_amount,
                           valid_until, status) VALUES
(1, 10001, 'B20261010100001', 1, 1, '燃脂跳操·Zumba 热舞',  '悦动·大学城店', 1, 'TIMES',     1,    1, 68.00, NOW() + INTERVAL 27 DAY, 'USED_UP'),
(2, 10001, 'B20261010100001', 1, 1, '燃脂跳操·Zumba 热舞',  '悦动·大学城店', 1, 'TIMES',     1,    0, 68.00, NOW() + INTERVAL 27 DAY, 'ACTIVE'),
-- ★valid_until = NULL 的长期凭证（FOREVER 形态），过期任务必须跳过它
(3, 10001, 'B20261011100002', 4, 3, '动感单车·极速卡路里',  '悦动·高新店',   1, 'UNLIMITED', NULL, 2, 19.90, NULL,                  'ACTIVE'),
(4, 10002, 'B20261012100003', 9, 3, '私教·增肌入门 2 小时', '悦动·高新店',   2, 'TIMES',     2,    0, 248.00, NOW() + INTERVAL 59 DAY, 'ACTIVE');

-- 已完成的两节 + 一张待上课的预约（含占用格子）
INSERT INTO t_reservation (id, reservation_no, user_id, entitlement_id, order_no, course_id, store_id, coach_id,
                           coach_name, course_name, store_name, lesson_hours, start_time, end_time,
                           status, cancel_deadline, verify_code, verified_at, finished_at) VALUES
(1, 'R20261012100001', 10001, 1, 'B20261010100001', 1, 1, 1, 'Lily', '燃脂跳操·Zumba 热舞', '悦动·大学城店', 1,
 NOW() - INTERVAL 2 DAY + INTERVAL 19 HOUR, NOW() - INTERVAL 2 DAY + INTERVAL 20 HOUR,
 'FINISHED', NOW() - INTERVAL 2 DAY + INTERVAL 18 HOUR, '4829157306', NOW() - INTERVAL 2 DAY + INTERVAL 19 HOUR + INTERVAL 5 MINUTE, NOW() - INTERVAL 2 DAY + INTERVAL 20 HOUR),
(2, 'R20261013100002', 10001, 3, 'B20261011100002', 4, 3, 4, '大鹏', '动感单车·极速卡路里', '悦动·高新店', 1,
 NOW() + INTERVAL 6 HOUR, NOW() + INTERVAL 7 HOUR,
 'BOOKED', NOW() + INTERVAL 5 HOUR, '9137205864', NULL, NULL),
-- 一张两小时的预约（占 2 个格子，用来演示多格占用）
(3, 'R20261014100003', 10002, 4, 'B20261012100003', 9, 3, 5, 'Alan', '私教·增肌入门 2 小时', '悦动·高新店', 2,
 NOW() + INTERVAL 1 DAY + INTERVAL 14 HOUR, NOW() + INTERVAL 1 DAY + INTERVAL 16 HOUR,
 'BOOKED', NOW() + INTERVAL 1 DAY + INTERVAL 13 HOUR, '2648013957', NULL, NULL);

-- 占用格子：与上面三张预约一一对应（预约 1 已完成所以格子保留，这是设计里的正确行为）
INSERT INTO t_coach_busy (coach_id, store_id, start_time, reservation_id, user_id) VALUES
(1, 1, NOW() - INTERVAL 2 DAY + INTERVAL 19 HOUR, 1, 10001),
(4, 3, NOW() + INTERVAL 6 HOUR,                   2, 10001),
(5, 3, NOW() + INTERVAL 1 DAY + INTERVAL 14 HOUR, 3, 10002),
(5, 3, NOW() + INTERVAL 1 DAY + INTERVAL 15 HOUR, 3, 10002);

-- 已完成的预约可以评价了（订单 1 的那条评价）
INSERT INTO t_rating (order_no, user_id, course_id, store_id, coach_id, coach_name, score, comment, status) VALUES
('B20261010100001', 10001, 1, 1, 1, 'Lily', 5, 'Lily 课上一刻不停，节奏带得很好，就是音乐声音略大。', 1);

-- 三条不同门店的评价（覆盖多评分的分布 + 让贝叶斯平均能看出效果）
INSERT INTO t_rating (order_no, user_id, course_id, store_id, coach_id, coach_name, score, comment, status) VALUES
('B20261011100002', 10001, 4, 3, 4, '大鹏', 4, '高新店器械确实全，单车房氛围很好，晚高峰人略多。',   1),
('B20261012100003', 10002, 9, 3, 5, 'Alan', 5, 'Alan 把动作模式讲得很细，两小时一点不枯燥。',         1);

-- 评分汇总（与上面三条评价对得上，手工算好：大学城 5/1；高新 4+5=9/2）
INSERT INTO t_store_rating_stat (store_id, rating_sum, rating_count) VALUES (1, 5, 1), (3, 9, 2);
INSERT INTO t_course_rating_stat (course_id, rating_sum, rating_count) VALUES (1, 5, 1), (4, 4, 1), (9, 5, 1);
INSERT INTO t_coach_rating_stat (coach_id, rating_sum, rating_count) VALUES (1, 5, 1), (4, 4, 1), (5, 5, 1);

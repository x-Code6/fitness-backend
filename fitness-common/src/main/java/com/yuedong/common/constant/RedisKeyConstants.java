package com.yuedong.common.constant;

/**
 * Redis Key 统一管理。
 *
 * <p>来源：《后端架构设计》§6 的 Key 表，以及《数据库设计与功能实现说明》第四部分 4.5
 * （v2 演进设计里新增与变更的 key 以 4.5 为准，落到这里）。</p>
 *
 * <p>设计约定：</p>
 * <ul>
 *   <li>前缀 + 业务 id 组成完整 key，参数化方法保证拼接格式全局一致；</li>
 *   <li>秒杀库存/购买资格的 key 由 course（预热写入）和 order（Lua 扣减）两个服务共同使用，
 *       必须共享同一常量来源，防止改名不同步；</li>
 *   <li>每个常量标注使用方与类型，便于容量评估和排查；</li>
 *   <li>★注释只描述代码真正做的事。旧版本这里写过"Lua 扣减""互斥重建锁"之类的契约，
 *       而当时对应功能并不存在——注释与实现脱节比没有注释更糟。</li>
 * </ul>
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() {
    }

    // ==================== 秒杀域（course 预热 + order 扣减，跨服务共享） ====================

    /** 秒杀库存，String，course 预热写入 / order Lua DECR */
    public static final String SECKILL_STOCK = "seckill:stock:";

    /** 一人一单资格集合，Set，course 预热清空 / order Lua SADD·SISMEMBER */
    public static final String SECKILL_BOUGHT = "seckill:bought:";

    // ★seckill:result:{seqId} 这条已经删除：它是"异步建单 + 前端轮询"时代的排队结果缓存。
    //   现在提交时订单同步落库，用户的答案在 t_booking 里，不需要第二份真相；
    //   留着只会让人以为"还有一个 seqId 状态机要维护"。

    public static String seckillStock(Long activityId) {
        return SECKILL_STOCK + activityId;
    }

    public static String seckillBought(Long activityId) {
        return SECKILL_BOUGHT + activityId;
    }

    // ==================== 课程缓存域（course 服务） ====================

    /** 课程详情缓存，String(JSON)，内嵌逻辑过期时间 */
    public static final String COURSE_DETAIL = "course:detail:";

    /** 课程详情空值缓存（防穿透），TTL 2 分钟 */
    public static final String COURSE_EMPTY = "course:empty:";

    /** 课程详情互斥重建锁（Redisson） */
    public static final String COURSE_LOCK = "course:lock:detail:";

    public static String courseDetail(Long id) {
        return COURSE_DETAIL + id;
    }

    public static String courseEmpty(Long id) {
        return COURSE_EMPTY + id;
    }

    // ==================== 优惠券域（course 服务承载，Lua 原子领券） ====================

    /** 券库存，String，Lua DECR */
    public static final String COUPON_STOCK = "coupon:stock:";

    /** 券已领集合，Set，一人一张 */
    public static final String COUPON_GOT = "coupon:got:";

    public static String couponStock(Long couponId) {
        return COUPON_STOCK + couponId;
    }

    public static String couponGot(Long couponId) {
        return COUPON_GOT + couponId;
    }

    // ==================== 门店域（course 服务） ====================

    /** 门店坐标，GEO，member=门店 id */
    public static final String STORE_GEO = "store:geo";

    // ==================== 积分域（point 服务） ====================

    /** 月度积分榜，ZSet，member=userId（打卡/完课/发动态 ZINCRBY） */
    public static final String RANK_MONTH = "rank:month:";

    /**
     * 打卡位图，Bitmap：<b>一人一 key</b>（不是"一人一月一 key"）。
     *
     * <p>★改这个形状是为了修两件事（docs D16）：
     * ① 旧 key {@code point:check:{yyyyMM}:{userId}} 的 offset 是"当月第几天"，
     *    跨月的连续打卡永远数不出来（10-01 的连续要看 9 月的位图，得再拼一个 key）；
     *    现在 offset = <b>距基准日的天数</b>，整条时间轴在同一个位图上连续，
     *    "连续多少天"就是从今天往低数连续 1。
     * ② key 数量从"用户数 × 月数"降到"用户数"。</p>
     *
     * <p>★它现在是<b>可重建的展示缓存</b>：打卡明细的真相在 {@code t_checkin_record}，
     * Redis 被清空时第一次打卡会从 DB 把位图补回来（见 CheckinServiceImpl#ensureBitmap），
     * 不再像旧版那样"宕机一次当月打卡全丢、连续天数无法重建"（P0-5）。</p>
     */
    public static final String POINT_CHECK = "point:check:";

    public static String rankMonth(String yyyyMM) {
        return RANK_MONTH + yyyyMM;
    }

    public static String pointCheck(Long userId) {
        return POINT_CHECK + userId;
    }

    // ==================== 埋点域（behavior 服务） ====================

    /** 课程点击热度榜，ZSet（yyyyMM 由使用方拼接），TTL 40 天 */
    public static final String BEHAVIOR_HOT_COURSE = "behavior:hot:course:";

    public static String behaviorHotCourse(String yyyyMM) {
        return BEHAVIOR_HOT_COURSE + yyyyMM;
    }

    // ==================== 社区域（community 服务） ====================

    /** Feed 推模式收件箱，ZSet：key=feed:inbox:{用户id}，member=动态id，score=发布时间戳，只留最近 1000 条 */
    public static final String FEED_INBOX = "feed:inbox:";

    public static String feedInbox(Long userId) {
        return FEED_INBOX + userId;
    }

    /**
     * 浏览量增量缓冲，Hash：field=动态id，value=未入账的浏览次数。
     *
     * <p>★写这个键的是浏览上报（一次 HINCRBY），读它的是定时任务：
     * 任务用 {@code HINCRBY field -delta} 原子取出后再 {@code view_count = view_count + delta} 入账。
     * <b>DB 的 view_count 才是账本，这里只是缓冲</b>——Redis 被清空最多丢掉未刷的增量，
     * 不会像"唯一真相在 Redis"那样把业务状态整块丢掉。</p>
     *
     * <p>★故意<b>不加 TTL 也不定期 DEL</b>：DEL 会在"读增量"与"删键"之间吃掉新产生的浏览。
     * Hash 的 field 数上限就是动态数，内存可控。</p>
     */
    public static final String POST_VIEW_DELTA = "post:view:delta";

    /**
     * 浏览去重集合，Set：key=post:viewed:{yyyyMMdd}，member={postId}:{userId}。
     *
     * <p>为什么必须去重：浏览量参与热度排序，<b>不去重的"廉价异步计数"等于给刷量开绿灯</b>。
     * 一人一帖一天只计一次，刷到第十次也只是同一个 member。</p>
     *
     * <p>TTL 给 3 天（不是 25 小时）：跨零点时先写 key 再设 TTL 的间隙、
     * 以及机器时钟略偏，都不该让用户"今天永远计不上"。过期即自动清理，不需要任务。</p>
     */
    public static final String POST_VIEWED = "post:viewed:";

    public static String postViewed(String yyyyMMdd) {
        return POST_VIEWED + yyyyMMdd;
    }

    // ==================== 登录态（user 签发 / gateway 校验，跨服务共享） ====================

    /**
     * 登录令牌白名单，String：key=login:token:{jti}，value=userId。
     *
     * <p>为什么需要它：JWT 本身无状态，一旦签发，在过期前谁都拦不住——"禁用某个用户"
     * "踢下线""改密后强制重新登录"这三件事做不到。加这一层后，网关每次验签通过还要再问一次
     * Redis "这个 jti 还在白名单里吗"，不在就直接 401。</p>
     *
     * <p>代价（要能说清）：等于把无状态的 JWT 用回了有状态的 session，每个请求多一次
     * Redis O(1) 查询；换来的是<b>可撤销性</b>。TTL 与 token 有效期对齐，
     * 所以不需要清理任务——过期即自动消失。</p>
     *
     * <p>★用 jti 而不是整个 token 当 key：token 有 200+ 字符，直接做 key 既浪费内存，
     * 也无法按用户反查（要禁用一个人时不知道该删哪些 key，见 LOGIN_USER）。</p>
     */
    public static final String LOGIN_TOKEN = "login:token:";

    /** 某用户当前所有在线 jti，Set。禁用/踢下线时按这个集合批量删 login:token:* */
    public static final String LOGIN_USER = "login:user:";

    /** 管理端登录令牌白名单，与用户端 key 空间分开（两套密钥、两套生命周期） */
    public static final String ADMIN_LOGIN_TOKEN = "admin:login:token:";

    /** 管理端某管理员当前所有在线 jti，Set */
    public static final String ADMIN_LOGIN_USER = "admin:login:user:";

    public static String loginToken(String jti) {
        return LOGIN_TOKEN + jti;
    }

    public static String loginUser(Long userId) {
        return LOGIN_USER + userId;
    }

    public static String adminLoginToken(String jti) {
        return ADMIN_LOGIN_TOKEN + jti;
    }

    public static String adminLoginUser(Long adminId) {
        return ADMIN_LOGIN_USER + adminId;
    }

    // ==================== 幂等/验证码（order / admin-server） ====================

    /** MQ 消费幂等，SETNX + TTL */
    public static final String IDEMPOTENT_MSG = "idempotent:msg:";

    /**
     * 接口防重窗口，SETNX + TTL（默认 5 秒），由 {@code IdempotentAspect} 写入。
     *
     * <p>与上面 IDEMPOTENT_MSG 的区别：MSG 防的是 MQ 消息重复投递（消费端职责），
     * API 防的是用户重复提交（入口职责），两者键空间分开，排查时一眼能分辨来源。</p>
     */
    public static final String IDEMPOTENT_API = "idempotent:api:";

    /** 管理端图形验证码，String，2 分钟一次一用 */
    public static final String ADMIN_CAPTCHA = "admin:captcha:";

    public static String idempotentMsg(String msgId) {
        return IDEMPOTENT_MSG + msgId;
    }

    /**
     * 接口防重 key：前缀 + 用户 + 请求路径 三段。
     *
     * @param prefix 业务前缀（一般取方法名，保证同接口不同动作互不干扰）
     * @param userId 当前登录用户，防重必须按用户隔离
     * @param path   请求路径，如 /coupon/receive
     */
    public static String idempotentApi(String prefix, Long userId, String path) {
        return IDEMPOTENT_API + prefix + ":" + userId + ":" + path;
    }

    /**
     * 下单令牌（一次性）。进入下单页时由 {@code /booking/preview} 发号写入，
     * 提交时由 {@code /booking/create} 用 {@code getAndDelete} 原子取回并删除。
     *
     * <p>★<b>一条令牌一个 key，不是一个用户一个 Set。</b>
     * Redis 的过期时间只能设在 key 上、不能设在集合的单个成员上，
     * 用 Set 存"这个用户手里的订单号"的话，用户每打开一次下单页就永久多一个成员，
     * 关掉页面也不会消失——集合会随时间无限增长，而且拿不到"10 分钟自动失效"这个语义。
     * 一条一个 key，TTL 天然就是"未提交的令牌自动回收"，不需要任何清理任务。</p>
     *
     * <p>★key 里带 userId 是必须的：不带的话，任何人拿到别人的订单号就能替别人提交、
     * 或者纯粹把那枚令牌用掉（拒绝服务）。带上之后别人的号在我的 key 空间里根本不存在。</p>
     *
     * <p>value 存发号时的毫秒时间戳。它不参与业务判断，只是排查用的抓手——
     * 用户投诉"点了没反应"时，能看出这枚令牌是刚发的还是 9 分钟前发的。</p>
     */
    public static final String ORDER_CREATE_TOKEN = "order:create:token:";

    public static String orderCreateToken(Long userId, String orderNo) {
        return ORDER_CREATE_TOKEN + userId + ":" + orderNo;
    }

    public static String adminCaptcha(String captchaId) {
        return ADMIN_CAPTCHA + captchaId;
    }
}

package com.yuedong.common.constant;

/**
 * Redis Key 统一管理（与《后端架构设计》§6 的 Key 表一一对应）。
 *
 * <p>设计约定：</p>
 * <ul>
 *   <li>前缀 + 业务 id 组成完整 key，参数化方法保证拼接格式全局一致；</li>
 *   <li>秒杀库存/购买资格的 key 由 course（预热写入）和 order（Lua 扣减）两个服务共同使用，
 *       必须共享同一常量来源，防止改名不同步；</li>
 *   <li>每个常量标注使用方与类型，便于容量评估和排查。</li>
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

    /** 秒杀排队结果，String（QUEUEING/SUCCESS:单号/FAIL:原因），order 写，TTL 10 分钟 */
    public static final String SECKILL_RESULT = "seckill:result:";

    public static String seckillStock(Long activityId) {
        return SECKILL_STOCK + activityId;
    }

    public static String seckillBought(Long activityId) {
        return SECKILL_BOUGHT + activityId;
    }

    public static String seckillResult(String seqId) {
        return SECKILL_RESULT + seqId;
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

    /** 当月打卡位图，Bitmap，offset = 日 - 1（yyyyMM 由使用方拼接） */
    public static final String POINT_CHECK = "point:check:";

    public static String rankMonth(String yyyyMM) {
        return RANK_MONTH + yyyyMM;
    }

    public static String pointCheck(String yyyyMM, Long userId) {
        return POINT_CHECK + yyyyMM + ":" + userId;
    }

    // ==================== 埋点域（behavior 服务） ====================

    /** 课程点击热度榜，ZSet（yyyyMM 由使用方拼接），TTL 40 天 */
    public static final String BEHAVIOR_HOT_COURSE = "behavior:hot:course:";

    public static String behaviorHotCourse(String yyyyMM) {
        return BEHAVIOR_HOT_COURSE + yyyyMM;
    }

    // ==================== 社区域（community 服务） ====================

    /** Feed 推模式收件箱，ZSet，score=发布时间戳 */
    public static final String FEED_INBOX = "feed:inbox:";

    public static String feedInbox(Long userId) {
        return FEED_INBOX + userId;
    }

    // ==================== 幂等/验证码（order / admin-server） ====================

    /** MQ 消费幂等，SETNX + TTL */
    public static final String IDEMPOTENT_MSG = "idempotent:msg:";

    /** 管理端图形验证码，String，2 分钟一次一用 */
    public static final String ADMIN_CAPTCHA = "admin:captcha:";

    public static String idempotentMsg(String msgId) {
        return IDEMPOTENT_MSG + msgId;
    }

    public static String adminCaptcha(String captchaId) {
        return ADMIN_CAPTCHA + captchaId;
    }
}

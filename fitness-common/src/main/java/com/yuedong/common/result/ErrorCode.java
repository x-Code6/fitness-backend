package com.yuedong.common.result;

import lombok.Getter;

/**
 * 统一错误码枚举。
 *
 * <p>编码规则（参考 HTTP 状态码思路，但独立于 HTTP）：</p>
 * <ul>
 *   <li>0              —— 成功</li>
 *   <li>4xxxx          —— 客户端/业务错误（参数、登录、权限、资源冲突等）</li>
 *   <li>5xxxx          —— 服务端错误</li>
 * </ul>
 * <p>错误码一旦对外发布就不能修改含义，新增业务错误请在对应号段追加。</p>
 */
@Getter
public enum ErrorCode {

    // ---------- 通用 ----------
    SUCCESS(0, "ok"),
    PARAM_ERROR(40000, "请求参数不正确"),
    REPEAT_SUBMIT(40001, "操作太快了，请稍后再试一次"),
    UNAUTHORIZED(40100, "尚未登录"),
    TOKEN_INVALID(40101, "登录已过期，请重新登录"),
    FORBIDDEN(40300, "无权访问该资源"),
    SYSTEM_ERROR(50000, "系统繁忙，请稍后重试"),

    // ---------- 用户服务（41000 ~ 41999） ----------
    PHONE_FORMAT_ERROR(41001, "手机号格式不正确"),
    USER_ALREADY_EXISTS(41002, "该手机号已注册"),
    USER_NOT_FOUND(41003, "用户不存在"),
    PHONE_OR_PASSWORD_ERROR(41004, "手机号或密码错误"),
    USER_DISABLED(41005, "账号已被禁用，请联系客服"),

    // ---------- 课程服务（42000 ~ 42999） ----------
    COURSE_NOT_FOUND(42001, "课程不存在"),
    // 42002 已删除：原 COURSE_FULL 服务"场次满员"，v2 取消场次概念后无生产者。
    // 号位保留空洞，不复用——错误码含义一旦对外就不能改，也不该被第二个语义抢走
    SECKILL_NOT_ACTIVE(42003, "该课程没有进行中的秒杀活动"),
    SECKILL_SOLD_OUT(42004, "手慢了，名额已被抢光"),
    SECKILL_DUPLICATE(42005, "每人每期限购一单"),
    VIDEO_NOT_FOUND(42006, "视频不存在"),
    COACH_NOT_FOUND(42007, "教练不存在或已停用"),
    COACH_WORK_TIME_INVALID(42008, "可约时间设置不合法"),
    COACH_NAME_DUPLICATE(42009, "该门店已存在同名教练"),
    COACH_NOT_QUALIFIED(42010, "该教练不开这门课"),
    STORE_NOT_FOUND(42011, "门店不存在或已关闭"),
    COURSE_NOT_ON_SALE(42012, "该课程当前不可购买（未开售或已停售）"),

    // ---------- 订单服务（43000 ~ 43999） ----------
    BOOKING_NOT_FOUND(43001, "订单不存在"),
    BOOKING_STATUS_ERROR(43002, "订单状态已变更，请刷新后重试"),
    // 43003 已删除：原 BOOKING_DUPLICATE 是"同人同课不许再下单"，v2 里购买就是买次数、
    // 想多上课就该多下单，去重反而挡路。号位同样不复用
    // 凭证轴（t_entitlement）。★43005 的文案要把"三种不能用的原因"合成一句人话：
    // 次数用完、已过期、已被退款任务置终态，用户看得见的差别只有一句话
    ENTITLEMENT_NOT_FOUND(43004, "凭证不存在"),
    ENTITLEMENT_UNUSABLE(43005, "该凭证已用完或已过期，请换一张或重新购买"),
    ENTITLEMENT_TIME_CONFLICT(43006, "凭证有效期与你选择的上课时间不匹配"),
    // 预约轴（t_reservation）与时段格子（t_coach_busy）
    RESERVATION_NOT_FOUND(43007, "预约记录不存在"),
    RESERVATION_STATUS_ERROR(43008, "该预约已取消或已完成，无法重复操作"),
    RESERVATION_TOO_LATE(43009, "距开课不足 1 小时，无法取消，请联系门店"),
    SLOT_NOT_BOOKABLE(43010, "该时段不可预约，请刷新后重新选择"),
    SLOT_ALREADY_TAKEN(43011, "该时段刚刚被预约，请刷新后重试"),
    SLOT_TIME_INVALID(43012, "上课时间不合法（须整点且在营业与教练可约区间内）"),
    // 购物车（t_cart_item）
    CART_ITEM_NOT_FOUND(43013, "购物车条目不存在或已结算"),
    CART_EMPTY(43014, "请先勾选要结算的课程"),
    CART_OVER_LIMIT(43015, "购物车条目已达上限"),
    // 评价（t_rating）
    RATING_ORDER_UNQUALIFIED(43016, "该订单还不能评价（未支付或还有凭证未使用完）"),
    RATING_DUPLICATE(43017, "本单已评价过，如需修改请联系客服"),
    RATING_NOT_FOUND(43018, "评价不存在"),
    RATING_COACH_MISMATCH(43019, "所选教练没有在这节课出现过"),

    // ---------- 积分服务（44000 ~ 44999） ----------
    CHECKIN_DUPLICATE(44001, "今天已经打过卡啦，明天再来"),
    GIFT_NOT_FOUND(44002, "商品不存在或已下架"),
    POINTS_NOT_ENOUGH(44003, "积分不足，去打卡/完课攒积分吧"),
    GIFT_STOCK_EMPTY(44004, "商品已兑完，看看别的吧"),

    // ---------- 优惠券（45000 ~ 45999） ----------
    COUPON_NOT_FOUND(45001, "优惠券不存在或已下架"),
    COUPON_SOLD_OUT(45002, "手慢了，优惠券已抢完"),
    COUPON_DUPLICATE(45003, "该优惠券每人限领一张"),
    COUPON_NOT_USABLE(45004, "优惠券不可用（过期/已使用/未达门槛）"),

    // ---------- 社区关注（46000 ~ 46999） ----------
    FOLLOW_SELF(46001, "不能关注自己哦"),
    FOLLOW_DUPLICATE(46002, "已关注该用户");

    /** 业务错误码 */
    private final int code;

    /** 默认提示信息（可被 BusinessException 覆盖为更具体的文案） */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}

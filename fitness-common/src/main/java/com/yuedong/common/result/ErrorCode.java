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
    COURSE_FULL(42002, "该时段名额已满，看看其他时间吧"),
    SECKILL_NOT_ACTIVE(42003, "该课程没有进行中的秒杀活动"),
    SECKILL_SOLD_OUT(42004, "手慢了，名额已被抢光"),
    SECKILL_DUPLICATE(42005, "每人每期限购一单"),
    VIDEO_NOT_FOUND(42006, "视频不存在"),

    // ---------- 订单服务（43000 ~ 43999） ----------
    BOOKING_NOT_FOUND(43001, "订单不存在"),
    BOOKING_STATUS_ERROR(43002, "订单状态已变更，请刷新后重试"),
    BOOKING_DUPLICATE(43003, "该课程你已预约过，请勿重复预约"),

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

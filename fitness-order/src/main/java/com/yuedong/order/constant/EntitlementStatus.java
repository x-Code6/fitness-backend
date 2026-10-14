package com.yuedong.order.constant;

/**
 * 凭证状态常量（<b>权益轴</b>，第二条状态机轴）。
 *
 * <p>★三条轴各管一件事，谁也别往谁身上套（docs 4.1.2）：</p>
 * <ul>
 *   <li>订单 {@code t_booking} 管钱：UNPAID / PAID / CANCELED / REFUNDED；</li>
 *   <li><b>本类</b>管权益：ACTIVE → USED_UP（次数用完）/ EXPIRED（过期）；</li>
 *   <li>预约 {@code t_reservation} 管时段：BOOKED / FINISHED / CANCELED。</li>
 * </ul>
 *
 * <p>"已过期"不是订单状态，"已上完课"不是凭证状态。旧模型里一张 {@code t_booking}
 * 同时背这三个语义，于是核销要改订单、取消要判断是否占过名额、退款要看有没有上过课，
 * 规则绕成一团 —— 这就是把它拆开的全部理由。</p>
 */
public final class EntitlementStatus {

    /** 可用。判断"还能不能约"要看次数：{@code total_times IS NULL OR used_times < total_times} */
    public static final String ACTIVE = "ACTIVE";

    /** 次数用完（只有 TIMES 形态会进这个状态；UNLIMITED 永不 USED_UP） */
    public static final String USED_UP = "USED_UP";

    /**
     * 已过期，由过期定时任务置入。
     * <p>★过期不等于退款：{@code used_times > 0} 时凭证置 EXPIRED 但订单不动（剩余不退），
     * 只有一次都没用过的才把订单推到 REFUNDED。</p>
     */
    public static final String EXPIRED = "EXPIRED";

    private EntitlementStatus() {
    }

    /** 状态码 → 用户可见文案，理由同 {@link BookingStatus#textOf}：文案是业务规则，不放前端字典表 */
    public static String textOf(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case ACTIVE -> "可用";
            case USED_UP -> "次数已用完";
            case EXPIRED -> "已过期";
            default -> status;
        };
    }
}

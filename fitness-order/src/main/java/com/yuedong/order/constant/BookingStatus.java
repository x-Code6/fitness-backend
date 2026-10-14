package com.yuedong.order.constant;

/**
 * 订单状态常量（订单轴）。
 *
 * <p>★v2 把"三条状态机轴"分开了，这里只是<b>钱</b>那一条：</p>
 * <table border="1">
 *   <tr><th>轴</th><th>表</th><th>状态</th></tr>
 *   <tr><td>订单（钱）</td><td>{@code t_booking}</td>
 *       <td>UNPAID → PAID；UNPAID → CANCELED；PAID → REFUNDED</td></tr>
 *   <tr><td>凭证（权益）</td><td>{@code t_entitlement}</td><td>ACTIVE / USED_UP / EXPIRED</td></tr>
 *   <tr><td>预约（时段）</td><td>{@code t_reservation}</td><td>BOOKED / FINISHED / CANCELED</td></tr>
 * </table>
 *
 * <p>旧实现里 {@code BOOKED} 与 {@code FINISHED} 混在订单上，等于"付了钱"和"去上过课"
 * 共用一个字段——所以核销要先改订单、取消要判断是否占名额，规则绕成一团。
 * 拆成三轴之后，"已过期"归凭证、"待上课"归预约，订单只管钱。</p>
 */
public final class BookingStatus {

    /** 待支付。下单即此状态，15 分钟未支付由延迟消息自动取消 */
    public static final String UNPAID = "UNPAID";

    /** 已支付，履约凭此生效（v2 由 PAID 代替旧的 BOOKED） */
    public static final String PAID = "PAID";

    /** 已取消：仅能从 UNPAID 进入。已支付的单不走取消，走下面的退款 */
    public static final String CANCELED = "CANCELED";

    /**
     * 已退款。
     * <p>★本项目支付与退款都是写死的模拟：不调任何支付网关，只把状态置成 REFUNDED，
     * 前端显示"已退款"。触发时机是"凭证过期且一次都没用"（见过期定时任务）。</p>
     */
    public static final String REFUNDED = "REFUNDED";

    private BookingStatus() {
    }

    /**
     * 状态码 → 用户可见文案。
     * <p>★文案放在后端而不是前端字典表：状态语义是业务规则，前端各维护一份就会不一致，
     * 而且 VO 里同时给 {@code status} 与 {@code statusText}，前端只负责渲染。</p>
     */
    public static String textOf(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case UNPAID -> "待支付";
            case PAID -> "已支付";
            case CANCELED -> "已取消";
            case REFUNDED -> "已退款";
            default -> status;
        };
    }
}

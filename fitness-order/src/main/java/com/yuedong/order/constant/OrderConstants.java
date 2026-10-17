package com.yuedong.order.constant;

/**
 * 订单域常量。
 *
 * <p>只放<b>两个以上类都要用</b>的值。单个类自用的常量留在自己类里（比如
 * {@code EntitlementServiceImpl} 的批量大小、{@code BookingServiceImpl} 的超时分钟数），
 * 全项目攒一个大 Constants 反而会让人分不清谁在用。</p>
 */
public final class OrderConstants {

    /**
     * 一次购买最多几份。
     * <p>★为什么必须限：份数直接决定出票张数（一张一次 INSERT），
     * 不封顶等于给外部一个"一个请求写一万行"的口子。取 10 是演示场景的合理上限。</p>
     *
     * <p>★这个数还要<b>下发给前端</b>（见 {@code /booking/preview} 的 maxQuantity），
     * 前端加号到 10 就禁用。以前服务端是静默 {@code Math.min} 截断：
     * 用户点了 20 份、后端按 10 份建单出票，不报错也不提示——
     * 这种"没报错的错"是最难发现的，现在改成超限直接报错。</p>
     */
    public static final int MAX_QUANTITY_PER_ITEM = 10;

    /** 预约可提前取消的时限（小时）。★与 t_reservation.cancel_deadline 的算法一致 */
    public static final int CANCEL_DEADLINE_HOURS = 1;

    /** 时段查询最多能往后看多少天 */
    public static final int SLOT_MAX_AHEAD_DAYS = 30;

    private OrderConstants() {
    }
}

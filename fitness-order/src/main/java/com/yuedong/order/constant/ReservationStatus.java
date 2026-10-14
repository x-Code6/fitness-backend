package com.yuedong.order.constant;

/**
 * 预约状态常量（<b>时段轴</b>，第三条状态机轴）。
 *
 * <p>★和另外两条轴分清楚：订单轴（钱）看 {@link BookingStatus}，
 * 凭证轴（权益）看 {@link EntitlementStatus}，本类只管"这一次约的时段走到了哪一步"。</p>
 *
 * <p>扣次时机 = 到点未取消（{@code BOOKED → FINISHED}，D5），所以
 * <b>核销不在这条轴上推进状态</b>：{@code verify_code} 只登记"人到店了"，
 * 教练确认与否都不影响是否扣次。这是他从"核销才扣次"改过来的决定，
 * 换来的是少一个强依赖 + 一条 UPDATE 就能结账。</p>
 */
public final class ReservationStatus {

    /** 已预约，待上课。可取消的窗口由 {@code cancel_deadline} 决定 */
    public static final String BOOKED = "BOOKED";

    /** 已完成：到点未取消即置此状态，同时扣掉凭证的一次 */
    public static final String FINISHED = "FINISHED";

    /** 已取消：不扣次，且把它占的整点格子全部删掉（时段回到空闲） */
    public static final String CANCELED = "CANCELED";

    private ReservationStatus() {
    }

    /** 状态码 → 用户可见文案（理由同 BookingStatus：文案是业务规则，不放前端字典表） */
    public static String textOf(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case BOOKED -> "待上课";
            case FINISHED -> "已完成";
            case CANCELED -> "已取消";
            default -> status;
        };
    }
}

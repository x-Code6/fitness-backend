package com.yuedong.order.constant;

/**
 * 预约订单状态常量（与前端 constants.js 的 BOOKING_STATUS 保持一致）。
 */
public final class BookingStatus {

    public static final String UNPAID = "UNPAID";
    public static final String BOOKED = "BOOKED";
    public static final String FINISHED = "FINISHED";
    public static final String CANCELED = "CANCELED";

    private BookingStatus() {
    }

    /** 该状态下的订单是否"占用名额"（取消/超时释放名额时用它判断） */
    public static boolean occupiesSlot(String status) {
        return UNPAID.equals(status) || BOOKED.equals(status);
    }
}

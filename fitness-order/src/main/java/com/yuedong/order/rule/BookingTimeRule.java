package com.yuedong.order.rule;

import com.yuedong.course.api.dto.CoachDTO;
import com.yuedong.course.api.dto.StoreDTO;

import java.time.LocalDate;

/**
 * 可约时间的唯一规则：<b>门店营业时间 ∩ 教练工时</b>。
 *
 * <p>★为什么单独抽成一个类：时段页（{@link com.yuedong.order.service.impl.SlotServiceImpl}）
 * 和创建预约（{@link com.yuedong.order.service.impl.ReservationServiceImpl}）必须用同一套判断。
 * 以前时段页用"门店∩教练"，建单只校验教练工时，直接调接口就能约到门店没营业的时间 ——
 * 页面显示不可约、接口却放行，是最典型的"两处规则漂移"。</p>
 *
 * <p>★24 小时营业的门店下界固定为 0，上界仍取教练下班时间：
 * 门店可以 24 小时开，但教练总要下班。</p>
 */
public final class BookingTimeRule {

    private BookingTimeRule() {
    }

    /** 教练这一天排不排班。workDays 形如 "1,2,3,4,5"（1=周一） */
    public static boolean worksOn(String workDays, LocalDate day) {
        if (workDays == null || workDays.isBlank()) {
            return false;
        }
        String dayOfWeek = String.valueOf(day.getDayOfWeek().getValue());
        for (String part : workDays.split(",")) {
            if (part.trim().equals(dayOfWeek)) {
                return true;
            }
        }
        return false;
    }

    /** 门店是不是 24 小时营业 */
    public static boolean is24h(StoreDTO store) {
        return store != null && store.getIs24h() != null && store.getIs24h() == 1;
    }

    /**
     * 可约区间下界：门店开门时间与教练上班时间取较晚的那个。
     * 24 小时门店固定为 0。
     */
    public static int openStart(StoreDTO store, CoachDTO coach) {
        if (is24h(store)) {
            return 0;
        }
        int storeStart = store == null ? 0 : valueOrZero(store.getOpenStartHour());
        int coachStart = coach == null ? 0 : valueOrZero(coach.getWorkStartHour());
        return Math.max(storeStart, coachStart);
    }

    /**
     * 可约区间上界：门店关门时间与教练下班时间取较早的那个。
     * 24 小时门店只受教练下班时间约束。
     */
    public static int openEnd(StoreDTO store, CoachDTO coach) {
        int coachEnd = coach == null ? 0 : valueOrZero(coach.getWorkEndHour());
        if (is24h(store)) {
            return coachEnd;
        }
        int storeEnd = store == null ? 0 : valueOrZero(store.getOpenEndHour());
        return Math.min(storeEnd, coachEnd);
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}

package com.yuedong.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.order.api.dto.AdminStatDTO;
import com.yuedong.order.constant.BookingStatus;
import com.yuedong.order.constant.EntitlementStatus;
import com.yuedong.order.constant.ReservationStatus;
import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.mapper.EntitlementMapper;
import com.yuedong.order.mapper.ReservationMapper;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.model.entity.Entitlement;
import com.yuedong.order.model.entity.Reservation;
import com.yuedong.order.model.vo.HotCourseRow;
import com.yuedong.order.model.vo.StatusCountRow;
import com.yuedong.order.model.vo.TrendRow;
import com.yuedong.order.service.AdminStatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 看板统计实现：全部是<b>只读聚合</b>，一条 SQL 出一个数，不在 Java 里捞全表再算。
 *
 * <p>★时间口径统一用 {@code [今天 00:00, 次日 00:00)} 这一对，
 * 由 Java 算好传给 SQL。理由：如果直接写 {@code DATE(created_at) = CURDATE()}，
 * created_at 上的索引就用不上了；而且"今天"到底是服务器时间还是数据库时间，
 * 一次定死在一处比到处写 CURDATE() 更容易对齐。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminStatServiceImpl implements AdminStatService {

    /** 热销榜取前几名 */
    private static final int HOT_LIMIT = 5;

    /** 热销与趋势的统计窗口（天） */
    private static final int RECENT_DAYS = 7;

    /** "即将到期"的天数窗口 */
    private static final int EXPIRING_DAYS = 7;

    private final BookingMapper bookingMapper;
    private final EntitlementMapper entitlementMapper;
    private final ReservationMapper reservationMapper;

    @Override
    public AdminStatDTO stat() {
        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        // ===== 第 1 步：成交三兄弟 =====
        Long todayOrders = bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
                .ge(Booking::getCreatedAt, dayStart)
                .lt(Booking::getCreatedAt, dayEnd));
        BigDecimal todayAmount = nullToZero(bookingMapper.sumPayAmount(BookingStatus.PAID, dayStart, dayEnd));
        BigDecimal refundedAmount = nullToZero(bookingMapper.sumRefundedAmount());

        // ===== 第 2 步：时段（分子在 order，分母"今天应开放多少格"在 course，由 admin-server 相加）=====
        Integer todaySlotUsed = nullToZeroInt(reservationMapper.sumSlotsBetween(dayStart, dayEnd));
        Long upcoming = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, ReservationStatus.BOOKED)
                // ★判"还没上完"用 end_time 而不是 start_time：正在上的课仍然算待上课，
                // 否则一节课上到一半就从待上课里消失了
                .gt(Reservation::getEndTime, now));

        // ===== 第 3 步：凭证 =====
        Long active = entitlementMapper.selectCount(new LambdaQueryWrapper<Entitlement>()
                .eq(Entitlement::getStatus, EntitlementStatus.ACTIVE));
        // FOREVER 的 valid_until 是 null，这一条范围条件天然把它排除在外（NULL 不参与比较）。
        // ★这就是"永久有效"必须存 null 而不是 9999-12-31 的代价：每一处过期判断都得想一遍
        Long expiringSoon = entitlementMapper.selectCount(new LambdaQueryWrapper<Entitlement>()
                .eq(Entitlement::getStatus, EntitlementStatus.ACTIVE)
                .ge(Entitlement::getValidUntil, now)
                .le(Entitlement::getValidUntil, now.plusDays(EXPIRING_DAYS)));

        // ===== 第 4 步：两个列表型聚合 =====
        List<AdminStatDTO.HotCourse> hotCourses = new ArrayList<>();
        for (HotCourseRow row : bookingMapper.selectHotCourses(RECENT_DAYS, HOT_LIMIT)) {
            hotCourses.add(AdminStatDTO.HotCourse.builder().name(row.getName()).sold(row.getSold()).build());
        }

        List<AdminStatDTO.TrendPoint> trend = new ArrayList<>();
        // ★传 RECENT_DAYS - 1：SQL 里是 created_at >= CURDATE() - N 天，
        //   含今天在内要 7 个点就只能往前 6 天
        for (TrendRow row : bookingMapper.selectTrend(RECENT_DAYS - 1)) {
            trend.add(AdminStatDTO.TrendPoint.builder()
                    .date(row.getDate())
                    .amount(nullToZero(row.getAmount()))
                    .count(row.getOrderCount())
                    .build());
        }

        // ===== 第 5 步：近 7 天订单状态分布（看板环形图）=====
        List<AdminStatDTO.StatusCount> statusDist = new ArrayList<>();
        for (StatusCountRow row : bookingMapper.selectStatusDist(RECENT_DAYS - 1)) {
            statusDist.add(AdminStatDTO.StatusCount.builder()
                    .status(row.getStatus())
                    .count(row.getCount())
                    .build());
        }

        return AdminStatDTO.builder()
                .todayOrderCount(todayOrders == null ? 0 : todayOrders.intValue())
                .todayAmount(todayAmount)
                .refundedAmount(refundedAmount)
                .todaySlotUsed(todaySlotUsed)
                .upcomingCount(upcoming == null ? 0 : upcoming.intValue())
                .activeEntitlement(active == null ? 0 : active.intValue())
                .expiringSoon(expiringSoon == null ? 0 : expiringSoon.intValue())
                .hotCourses(hotCourses)
                .trend(trend)
                .statusDist(statusDist)
                .build();
    }

    /** SUM 在无匹配行时返回 NULL 而不是 0，统一在这里兜住（否则看板显示空白） */
    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Integer nullToZeroInt(Integer value) {
        return value == null ? 0 : value;
    }
}

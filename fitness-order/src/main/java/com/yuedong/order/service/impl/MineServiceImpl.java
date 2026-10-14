package com.yuedong.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.order.constant.BookingStatus;
import com.yuedong.order.constant.EntitlementStatus;
import com.yuedong.order.constant.ReservationStatus;
import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.mapper.EntitlementMapper;
import com.yuedong.order.mapper.ReservationMapper;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.model.entity.Entitlement;
import com.yuedong.order.model.vo.MineSummaryVO;
import com.yuedong.order.service.EntitlementService;
import com.yuedong.order.service.MineService;
import com.yuedong.order.service.RatingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 四宫格实现。
 *
 * <p>★这一页是<b>三条状态轴同时出现</b>的地方：待支付来自订单轴、待使用来自凭证轴、
 * 已预约来自预约轴、待评价跨订单+凭证+评价三张表。
 * 拆表之后每个数字的定义都能一句话说清；拆表之前这四个数字要靠一个 status 列互相猜。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MineServiceImpl implements MineService {

    private final BookingMapper bookingMapper;
    private final EntitlementMapper entitlementMapper;
    private final ReservationMapper reservationMapper;
    private final EntitlementService entitlementService;
    private final RatingService ratingService;

    @Override
    public MineSummaryVO summary(Long userId) {
        LocalDateTime now = LocalDateTime.now();

        // ① 待支付：订单轴。WHERE user_id=? AND status='UNPAID'，命中 idx_user_status
        Long unpaid = bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getUserId, userId)
                .eq(Booking::getStatus, BookingStatus.UNPAID));

        // ② 待使用：凭证轴。先按 user + ACTIVE 收（命中 idx_user_status），
        //    次数与有效期两套 NULL 语义交给实体上的 usableAt 判（★不在这里再写一遍条件）
        List<Entitlement> actives = entitlementMapper.selectList(new LambdaQueryWrapper<Entitlement>()
                .eq(Entitlement::getUserId, userId)
                .eq(Entitlement::getStatus, EntitlementStatus.ACTIVE));
        int waitUse = 0;
        for (Entitlement entitlement : actives) {
            if (entitlement.usableAt(now)) {
                waitUse++;
            }
        }

        // ③ 已预约（即将上课）：预约轴。BOOKED 且还没下课
        Long booked = reservationMapper.selectCount(new LambdaQueryWrapper<com.yuedong.order.model.entity.Reservation>()
                .eq(com.yuedong.order.model.entity.Reservation::getUserId, userId)
                .eq(com.yuedong.order.model.entity.Reservation::getStatus, ReservationStatus.BOOKED)
                .gt(com.yuedong.order.model.entity.Reservation::getEndTime, now));

        // ④ 待评价：跨三张表。已支付 + 凭证全部结束 + 这条单没评过。
        //    ★实现是"取 PAID 单 → 批量取凭证 → 批量取已评单号"三条查询，
        //    而不是每单一次 EXISTS 子查询：N 单变 3 条 SQL，且都在同一库里
        List<Booking> paidOrders = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getUserId, userId)
                .eq(Booking::getStatus, BookingStatus.PAID)
                .orderByDesc(Booking::getId)
                .last("LIMIT 200"));
        int toRate = 0;
        if (!paidOrders.isEmpty()) {
            List<String> orderNos = new ArrayList<>(paidOrders.size());
            for (Booking booking : paidOrders) {
                orderNos.add(booking.getOrderNo());
            }
            Map<String, List<Entitlement>> byOrder = entitlementService.mapByOrderNos(userId, orderNos);
            Set<String> rated = ratingService.ratedOrderNos(userId, orderNos);
            for (String orderNo : orderNos) {
                List<Entitlement> entitlements = byOrder.get(orderNo);
                if (entitlements == null || entitlements.isEmpty() || rated.contains(orderNo)) {
                    continue;
                }
                boolean settled = true;
                for (Entitlement entitlement : entitlements) {
                    if (EntitlementStatus.ACTIVE.equals(entitlement.getStatus())) {
                        settled = false;
                        break;
                    }
                }
                if (settled) {
                    toRate++;
                }
            }
        }

        return MineSummaryVO.builder()
                .unpaidCount(unpaid == null ? 0 : unpaid.intValue())
                .waitUseCount(waitUse)
                .bookedCount(booked == null ? 0 : booked.intValue())
                .ratingCount(toRate)
                .build();
    }
}

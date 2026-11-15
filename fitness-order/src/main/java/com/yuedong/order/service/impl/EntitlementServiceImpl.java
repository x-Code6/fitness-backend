package com.yuedong.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.api.client.CourseClient;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.order.api.dto.EntitlementAdminDTO;
import com.yuedong.order.constant.EntitlementStatus;
import com.yuedong.order.constant.OrderConstants;
import com.yuedong.order.mapper.EntitlementMapper;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.model.entity.Entitlement;
import com.yuedong.order.model.vo.EntitlementExpireResultVO;
import com.yuedong.order.model.vo.EntitlementVO;
import com.yuedong.order.model.vo.ReservationCountRow;
import com.yuedong.order.mq.NotifyPublisher;
import com.yuedong.order.service.support.EntitlementExpireTxWriter;
import com.yuedong.order.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭证实现（权益轴）。
 *
 * <p>本类是"买"与"约"之间的桥：{@code BookingServiceImpl} 负责钱，
 * {@code ReservationServiceImpl} 负责时段，两边都只通过这里读写凭证，
 * 于是"次数"这件事在全项目只有一处加减（{@link EntitlementMapper#consumeOne}），
 * 不会出现两个服务各自扣一遍或谁都没扣。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntitlementServiceImpl implements EntitlementService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 过期任务单轮最多处理多少张。
     * <p>分批是为了防止一次 UPDATE 锁太久把在线预约拖慢；一轮 200 张、每小时跑，
     * 演示数据量下永远跑不完一轮也没关系——它下一轮接着处理（CAS 保证不重复扣）。</p>
     */
    private static final int EXPIRE_BATCH_SIZE = 200;

    private final EntitlementMapper entitlementMapper;
    /** 只为"整页凭证各被约了几次"这一个聚合查询服务，不写任何预约状态 */
    private final com.yuedong.order.mapper.ReservationMapper reservationMapper;
    private final CourseClient courseClient;
    private final NotifyPublisher notifyPublisher;
    /** 过期 + 按张退款的事务段（单独 Bean 才能让 @Transactional 生效） */
    private final EntitlementExpireTxWriter entitlementExpireTxWriter;

    // =====================================================================
    // 出票
    // =====================================================================
    @Override
    public List<Entitlement> grantForPaidOrder(Booking booking) {
        // ===== 第 1 步：定份数与每张的次数 =====
        int quantity = booking.getQuantity() == null || booking.getQuantity() < 1
                ? 1 : Math.min(booking.getQuantity(), OrderConstants.MAX_QUANTITY_PER_ITEM);
        boolean unlimited = "UNLIMITED".equals(booking.getSaleType());
        // TIMES：times_granted 是下单那一刻从课程快照来的"一单给几次"；
        // 历史数据里它可能是 null（老脚本），按 1 兜底而不是让凭证永远约不了
        Integer totalTimes = unlimited ? null
                : (booking.getTimesGranted() == null || booking.getTimesGranted() < 1
                ? 1 : booking.getTimesGranted());

        // ===== 第 2 步：算有效期（唯一一处需要跨服务读的地方，只读不写）=====
        LocalDateTime payTime = booking.getPayTime() == null ? LocalDateTime.now() : booking.getPayTime();
        LocalDateTime validUntil = resolveValidUntil(booking, payTime);

        // ===== 第 3 步：把实付金额按份数分摊到每张凭证 =====
        // 例：买 2 份实付 150 元 → 每张 75 元；除不尽时余数放到最后一张，
        // 保证 N 张的 paid_amount 之和严格等于订单 pay_amount。
        // 以后退款按这张凭证的 paid_amount 退，既不会按原价多退，也不会少退一分钱。
        BigDecimal totalPaid = booking.getPayAmount() == null
                ? BigDecimal.ZERO : booking.getPayAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal unitPaid = totalPaid.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.DOWN);
        BigDecimal allocatedPaid = BigDecimal.ZERO;

        // ===== 第 4 步：逐张出票。★多份不合并（D4），买 3 份就是 3 张独立凭证 =====
        List<Entitlement> granted = new ArrayList<>(quantity);
        for (int i = 0; i < quantity; i++) {
            Entitlement entitlement = new Entitlement();
            entitlement.setUserId(booking.getUserId());
            entitlement.setOrderNo(booking.getOrderNo());
            entitlement.setCourseId(booking.getCourseId());
            entitlement.setStoreId(booking.getStoreId());
            entitlement.setCourseName(booking.getCourseName());
            entitlement.setStoreName(booking.getStoreName());
            entitlement.setLessonHours(booking.getLessonHours());
            entitlement.setSaleType(booking.getSaleType());
            entitlement.setTotalTimes(totalTimes);
            entitlement.setUsedTimes(0);

            BigDecimal paidAmount;
            if (i == quantity - 1) {
                // 最后一张拿余数：33.33 + 33.33 + 33.34 = 100.00
                paidAmount = totalPaid.subtract(allocatedPaid);
            } else {
                paidAmount = unitPaid;
                allocatedPaid = allocatedPaid.add(paidAmount);
            }
            entitlement.setPaidAmount(paidAmount);
            entitlement.setRefundAmount(BigDecimal.ZERO);

            entitlement.setValidUntil(validUntil);
            // ★全部从 ACTIVE 起步，终态由扣次/过期两条 CAS 推进
            entitlement.setStatus(EntitlementStatus.ACTIVE);
            entitlement.setCreatedAt(LocalDateTime.now());
            // 增：写 t_entitlement，一单一份。与"订单置 PAID"在同一个事务里（见 BookingServiceImpl#pay）
            entitlementMapper.insert(entitlement);
            granted.add(entitlement);
        }

        log.info("支付成功已出票: orderNo={}, 张数={}, 每张次数={}, 有效期至={}",
                booking.getOrderNo(), quantity, unlimited ? "不限" : totalTimes, validUntil);
        return granted;
    }

    /**
     * 按课程在该门店配置的可使用窗口算出凭证截止时刻。
     *
     * <p>★三种类型读三组列，<b>不做"两个都有值就取交集"的隐式规则</b>
     * （t_course_store 的列注释里已经写明理由：隐式规则一定会被误读）。</p>
     *
     * <p>★取不到有效期时<b>让支付失败并回滚</b>，不再静默发永久凭证：
     * 一次跨服务抖动换一张永久卡，是会长期漏钱的兜底。支付保持 UNPAID，用户稍后重试即可。</p>
     */
    private LocalDateTime resolveValidUntil(Booking booking, LocalDateTime payTime) {
        CourseDTO course;
        try {
            // 查课程在该门店的第二层时间窗：只读，不产生任何远端写入，失败无需补偿
            course = courseClient.detail(booking.getCourseId(), booking.getStoreId()).getData();
        } catch (Exception e) {
            log.error("取课程有效期失败，支付回滚等待重试: orderNo={}, courseId={}",
                    booking.getOrderNo(), booking.getCourseId(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "课程有效期获取失败，请稍后重试");
        }
        if (course == null || course.getValidType() == null) {
            log.warn("课程未配置有效期，支付回滚: orderNo={}, courseId={}, storeId={}",
                    booking.getOrderNo(), booking.getCourseId(), booking.getStoreId());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "课程有效期配置缺失，请联系门店");
        }

        switch (course.getValidType()) {
            // FOREVER：常年日常价课，valid_until 存 null。★过期扫描必须跳过 null
            case "FOREVER":
                return null;
            // DAYS：自<b>支付时刻</b>起 N 天。★起算点是付款而不是下单，两者可能差 15 分钟
            case "DAYS":
                if (course.getValidDays() == null || course.getValidDays() < 1) {
                    log.warn("valid_type=DAYS 但没配 valid_days，支付回滚: courseId={}", booking.getCourseId());
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "课程有效期配置不完整，请稍后重试");
                }
                return payTime.plusDays(course.getValidDays());
            // RANGE：固定窗口（短期训练营），与付款时刻无关，只看截止
            case "RANGE":
                if (course.getUseEndTime() == null) {
                    log.warn("valid_type=RANGE 但没配 use_end_time，支付回滚: courseId={}", booking.getCourseId());
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "课程有效期配置不完整，请稍后重试");
                }
                return course.getUseEndTime();
            default:
                log.warn("未知的 valid_type={}，支付回滚: courseId={}",
                        course.getValidType(), booking.getCourseId());
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "课程有效期配置异常，请联系门店");
        }
    }

    // =====================================================================
    // 查询与查验
    // =====================================================================
    @Override
    public List<EntitlementVO> listMine(Long userId, Long courseId, Boolean usableOnly) {
        // 查我的凭证：WHERE user_id=? [+ course_id=?] ORDER BY id DESC
        // 命中 idx_user_status(user_id, status, valid_until) 的最左前缀 user_id；
        // 不带 usableOnly 时不加 status 条件——列表要看历史（用完/过期的也是用户的记录）
        List<Entitlement> rows = entitlementMapper.selectList(new LambdaQueryWrapper<Entitlement>()
                .eq(Entitlement::getUserId, userId)
                .eq(courseId != null, Entitlement::getCourseId, courseId)
                .orderByDesc(Entitlement::getId));

        // 一次 GROUP BY 拿全部"已约未上"节数：★不在循环里逐张查，那是 N+1 的老路
        Map<Long, Integer> pendingMap = pendingCounts(rows.stream().map(Entitlement::getId).toList());

        LocalDateTime now = LocalDateTime.now();
        List<EntitlementVO> result = new ArrayList<>(rows.size());
        for (Entitlement row : rows) {
            int pending = pendingMap.getOrDefault(row.getId(), 0);
            // usableOnly 在内存里过滤而不是加 SQL 条件：
            // "可用"要判次数、有效期、占用三套语义（前两个还是 NULL 语义），写进 WHERE 会变成
            // 一条谁也读不懂的 SQL，而这里的量级（一个人几十张凭证）根本不需要数据库来帮这个筛
            if (Boolean.TRUE.equals(usableOnly) && !row.bookableAt(now, pending)) {
                continue;
            }
            result.add(toVO(row, pending));
        }
        return result;
    }

    @Override
    public Map<Long, Integer> pendingCounts(java.util.Collection<Long> entitlementIds) {
        if (entitlementIds == null || entitlementIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (ReservationCountRow row : reservationMapper.countPendingByEntitlements(new ArrayList<>(entitlementIds))) {
            counts.put(row.getEntitlementId(), row.getReservationCount());
        }
        return counts;
    }

    @Override
    public Entitlement loadUsable(Long userId, Long entitlementId) {
        // 查一张凭证：WHERE id=? AND user_id=?。★user_id 不能省，
        // 只按 id 查就成了"猜到凭证 id 就能蹭别人的课"（水平越权）
        Entitlement entitlement = entitlementMapper.selectOne(new LambdaQueryWrapper<Entitlement>()
                .eq(Entitlement::getId, entitlementId)
                .eq(Entitlement::getUserId, userId));
        if (entitlement == null) {
            throw new BusinessException(ErrorCode.ENTITLEMENT_NOT_FOUND);
        }
        int pending = pendingCounts(List.of(entitlementId)).getOrDefault(entitlementId, 0);
        if (entitlement.bookableAt(LocalDateTime.now(), pending)) {
            return entitlement;
        }
        // ★两种"不能约"要分开告诉用户，因为下一步动作完全不同：
        //   挂着待上的课 → 去上课或取消原预约；次数真用完/过期 → 得重新买
        if (pending >= 1) {
            throw new BusinessException(ErrorCode.ENTITLEMENT_PENDING_FULL);
        }
        throw new BusinessException(ErrorCode.ENTITLEMENT_UNUSABLE);
    }

    @Override
    public Map<String, List<Entitlement>> mapByOrderNos(Long userId, List<String> orderNos) {
        Map<String, List<Entitlement>> grouped = new HashMap<>();
        if (orderNos == null || orderNos.isEmpty()) {
            return grouped;
        }
        // 一次 IN 查完这批订单的凭证：★绝不在循环里逐单查（订单列表 10 行就变成 10 次查询）
        // 命中 idx_order(order_no)，等值 IN 能用上索引
        List<Entitlement> rows = entitlementMapper.selectList(new LambdaQueryWrapper<Entitlement>()
                .eq(Entitlement::getUserId, userId)
                .in(Entitlement::getOrderNo, orderNos));
        for (Entitlement row : rows) {
            grouped.computeIfAbsent(row.getOrderNo(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    @Override
    public boolean isOrderFinished(String orderNo) {
        // 条件 1：这一单确实出过票（COUNT 命中 idx_order）
        Long total = entitlementMapper.selectCount(new LambdaQueryWrapper<Entitlement>()
                .eq(Entitlement::getOrderNo, orderNo));
        if (total == null || total == 0) {
            return false;
        }
        // 条件 2：没有任何一张还在 ACTIVE。★用完(USED_UP)与过期(EXPIRED)都算"结束"，
        // 所以判断只排 ACTIVE，不去枚举终态——将来加状态也不用改这里
        Long active = entitlementMapper.selectCount(new LambdaQueryWrapper<Entitlement>()
                .eq(Entitlement::getOrderNo, orderNo)
                .eq(Entitlement::getStatus, EntitlementStatus.ACTIVE));
        return active == null || active == 0;
    }

    // =====================================================================
    // 过期与退款（流程 F）
    // =====================================================================
    @Override
    public int expireOverdue() {
        LocalDateTime now = LocalDateTime.now();

        // ===== 第 1 步：圈出过期候选 =====
        // 查询里已经带三个条件：status=ACTIVE、valid_until IS NOT NULL、valid_until < now；
        // 另外还排除了"还有 BOOKED 预约"的凭证，避免课还没上完就先过期退款。
        // 条件写在 Mapper 里，命中 idx_expire(status, valid_until)。
        List<Entitlement> overdue = entitlementMapper.selectExpiredWithoutPending(now, EXPIRE_BATCH_SIZE);
        if (overdue.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (Entitlement entitlement : overdue) {
            // ===== 第 2 步：置过期 + 按张退款，同一个事务 =====
            // 事务里先 CAS 置 EXPIRED，再按这张凭证的 paid_amount 退款；
            // 任一步失败整体回滚，下一轮任务还能重新捞到它。
            EntitlementExpireResultVO result =
                    entitlementExpireTxWriter.expireAndRefund(entitlement, now);
            if (!result.isExpired()) {
                // 被别的实例/上一轮处理过了
                continue;
            }
            processed++;

            // ===== 第 3 步：按处理结果发通知 =====
            BigDecimal refundAmount = result.getRefundAmount();
            if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
                notifyPublisher.publishBookingNotify(entitlement.getUserId(), "凭证已过期，已退款",
                        "「" + entitlement.getCourseName() + "」的凭证已到期，"
                                + "未使用部分退款 ¥" + refundAmount.setScale(2, RoundingMode.HALF_UP) + "。");
            } else if (result.getUsedTimes() == 0) {
                // 0 元凭证（或已经退过）：没有金额可退，只告知过期
                notifyPublisher.publishBookingNotify(entitlement.getUserId(), "凭证已过期",
                        "「" + entitlement.getCourseName() + "」的凭证已到期。");
            } else {
                // 用过一点的：剩余次数不退（D7 明确规则）
                notifyPublisher.publishBookingNotify(entitlement.getUserId(), "凭证已过期",
                        "「" + entitlement.getCourseName() + "」的凭证已到期，已用 "
                                + result.getUsedTimes() + " 次，剩余次数按规则不予退还。");
            }

            log.info("凭证过期处理完成: entitlementId={}, orderNo={}, 已用={}, 退款={}",
                    entitlement.getId(), entitlement.getOrderNo(),
                    result.getUsedTimes(), refundAmount);
        }

        if (processed > 0) {
            log.info("凭证过期扫描完成: 本轮处理 {} 张（上限 {}）", processed, EXPIRE_BATCH_SIZE);
        }
        return processed;
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /** 实体 → C 端 VO（字段名对齐前端 entitlementVO，派生值全部在这里算完） */
    @Override
    public EntitlementVO toVO(Entitlement entitlement, int pendingTimes) {
        boolean unlimited = entitlement.getTotalTimes() == null;
        int used = entitlement.getUsedTimes() == null ? 0 : entitlement.getUsedTimes();
        // ★剩余次数 = 总 - 已上课 - 占用中。少了后两项之一，就会出现
        //   "页面上还显示可用 1 次，点进去约第三节却被拒"这种自相矛盾的界面
        Integer remain = unlimited ? null : Math.max(0, entitlement.remainTimes(pendingTimes));
        boolean bookable = entitlement.bookableAt(LocalDateTime.now(), pendingTimes);

        return EntitlementVO.builder()
                .entitlementId(entitlement.getId())
                .orderNo(entitlement.getOrderNo())
                .courseId(entitlement.getCourseId())
                .courseName(entitlement.getCourseName())
                .storeId(entitlement.getStoreId())
                .storeName(entitlement.getStoreName())
                .lessonHours(entitlement.getLessonHours())
                .saleType(entitlement.getSaleType())
                .totalTimes(entitlement.getTotalTimes())
                .usedTimes(used)
                .paidAmount(entitlement.getPaidAmount())
                .refundAmount(entitlement.getRefundAmount())
                .refundText(refundText(entitlement))
                // 占用中：0 就给 null，前端不渲染这一行（"0 节待上课"是一句没用的话）
                .pendingTimes(pendingTimes == 0 ? null : pendingTimes)
                .pendingText(pendingTimes == 0 ? null : pendingTimes + " 节待上课，去上完或先取消才能再约")
                .remainTimes(remain)
                .unlimited(unlimited)
                // ★永久有效给 null，不给 9999-12-31 这种魔法值：
                // 前端判空比判"是不是那个魔法日期"可靠得多
                .validUntil(entitlement.getValidUntil() == null ? null : entitlement.getValidUntil().format(TIME_FMT))
                .validUntilText(formatValidUntil(entitlement.getValidUntil()))
                .status(entitlement.getStatus())
                .statusText(EntitlementStatus.textOf(entitlement.getStatus()))
                .canBook(bookable)
                // 同名课多张凭证靠这一行区分（买两次就有两条"燃脂跳操"），尾号 + 购买日期足够认出来
                .subTitle("订单尾号 " + tailOf(entitlement.getOrderNo())
                        + " · " + (entitlement.getCreatedAt() == null ? "" : formatDay(entitlement.getCreatedAt()) + " 购买"))
                .build();
    }

    /**
     * 凭证退款文案。
     * <p>没退过返回 null，前端就不渲染这一行；退过但金额为 0（0 元单）只说"已过期"。</p>
     */
    private String refundText(Entitlement entitlement) {
        if (entitlement.getRefundedAt() == null) {
            return null;
        }
        BigDecimal amount = entitlement.getRefundAmount() == null
                ? BigDecimal.ZERO : entitlement.getRefundAmount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return "已过期";
        }
        return "已退款 ¥" + amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** 有效期文案：null 必须显示"长期有效"，不能显示空串——用户看到空白会以为凭证坏了 */
    private String formatValidUntil(LocalDateTime validUntil) {
        return validUntil == null ? "长期有效" : validUntil.format(TIME_FMT) + " 到期";
    }

    private String formatDay(LocalDateTime time) {
        return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    /** 取单号后 4 位做展示尾号；长度不足就原样返回，不给前端留 undefined */
    private String tailOf(String orderNo) {
        if (orderNo == null) {
            return "";
        }
        return orderNo.length() <= 4 ? orderNo : orderNo.substring(orderNo.length() - 4);
    }

    // =====================================================================
    // 管理端
    // =====================================================================

    @Override
    public PageDTO<EntitlementAdminDTO> adminPage(String status, String keyword, Long userId,
                                                  Integer page, Integer size) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        String kw = hasKeyword ? keyword.trim() : null;

        Page<Entitlement> result = entitlementMapper.selectPage(
                new Page<>(page == null || page < 1 ? 1 : page, size == null || size < 1 ? 10 : Math.min(size, 50)),
                new LambdaQueryWrapper<Entitlement>()
                        .eq(status != null && !status.isBlank() && !"ALL".equals(status),
                                Entitlement::getStatus, status)
                        .eq(userId != null, Entitlement::getUserId, userId)
                        // ★关键词包进括号：LIKE 多字段 OR 不括起来会抢走外面 eq 条件的作用域
                        .and(hasKeyword, item -> item.like(Entitlement::getOrderNo, kw)
                                .or().like(Entitlement::getCourseName, kw))
                        .orderByDesc(Entitlement::getId));

        List<EntitlementAdminDTO> rows = new ArrayList<>();
        for (Entitlement entitlement : result.getRecords()) {
            rows.add(toAdminDTO(entitlement));
        }
        fillReservationCounts(rows);
        return PageDTO.of(rows, result.getTotal());
    }

    @Override
    public void fillReservationCounts(List<EntitlementAdminDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(EntitlementAdminDTO::getId).toList();
        Map<Long, Integer> counts = new HashMap<>();
        for (ReservationCountRow row : reservationMapper.countByEntitlements(ids)) {
            counts.put(row.getEntitlementId(), row.getReservationCount());
        }
        for (EntitlementAdminDTO row : rows) {
            // GROUP BY 不会返回"一次都没约"的那些凭证，所以这里必须给默认 0
            row.setReservationCount(counts.getOrDefault(row.getId(), 0));
        }
    }

    @Override
    public EntitlementAdminDTO toAdminDTO(Entitlement entitlement) {
        // 剩余次数：UNLIMITED 形态没有"剩余"这个概念，给 null 而不是 total，
        // 免得前端拿到一个看起来像数字的东西去显示"剩 999999 次"
        Integer remain = null;
        if (!"UNLIMITED".equals(entitlement.getSaleType()) && entitlement.getTotalTimes() != null) {
            remain = Math.max(entitlement.getTotalTimes()
                    - (entitlement.getUsedTimes() == null ? 0 : entitlement.getUsedTimes()), 0);
        }
        return EntitlementAdminDTO.builder()
                .id(entitlement.getId())
                .userId(entitlement.getUserId())
                .orderNo(entitlement.getOrderNo())
                .courseId(entitlement.getCourseId())
                .courseName(entitlement.getCourseName())
                .storeId(entitlement.getStoreId())
                .storeName(entitlement.getStoreName())
                .lessonHours(entitlement.getLessonHours())
                .saleType(entitlement.getSaleType())
                .totalTimes(entitlement.getTotalTimes())
                .usedTimes(entitlement.getUsedTimes())
                .paidAmount(entitlement.getPaidAmount())
                .refundAmount(entitlement.getRefundAmount())
                .refundedAt(entitlement.getRefundedAt())
                .remainTimes(remain)
                .validUntil(entitlement.getValidUntil())
                .validUntilText(formatValidUntil(entitlement.getValidUntil()))
                .status(entitlement.getStatus())
                .statusText(EntitlementStatus.textOf(entitlement.getStatus()))
                .createdAt(entitlement.getCreatedAt())
                .build();
    }
}

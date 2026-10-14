package com.yuedong.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.api.client.CourseClient;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.order.constant.BookingStatus;
import com.yuedong.order.constant.EntitlementStatus;
import com.yuedong.order.constant.OrderConstants;
import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.mapper.EntitlementMapper;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.model.entity.Entitlement;
import com.yuedong.order.model.vo.EntitlementVO;
import com.yuedong.order.mq.NotifyPublisher;
import com.yuedong.order.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    private final BookingMapper bookingMapper;
    private final CourseClient courseClient;
    private final NotifyPublisher notifyPublisher;

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

        // ===== 第 3 步：逐张出票。★多份不合并（D4），买 3 份就是 3 张独立凭证 =====
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
     * <p>Feign 失败或课程没配到这家店时返回 null（= 永久），这是<b>故意偏向用户</b>的兜底：
     * 凭证发不出去比有效期算错严重得多，宁可让用户多能约一段时间，也不能收了钱给一张用不了的课。</p>
     */
    private LocalDateTime resolveValidUntil(Booking booking, LocalDateTime payTime) {
        CourseDTO course;
        try {
            // 查课程在该门店的第二层时间窗：只读，不产生任何远端写入，失败无需补偿
            course = courseClient.detail(booking.getCourseId(), booking.getStoreId()).getData();
        } catch (Exception e) {
            log.error("取课程有效期失败，凭证按永久的发: orderNo={}, courseId={}",
                    booking.getOrderNo(), booking.getCourseId(), e);
            return null;
        }
        if (course == null || course.getValidType() == null) {
            log.warn("课程未配置有效期，凭证按永久的发: orderNo={}, courseId={}, storeId={}",
                    booking.getOrderNo(), booking.getCourseId(), booking.getStoreId());
            return null;
        }

        switch (course.getValidType()) {
            // FOREVER：常年日常价课，valid_until 存 null。★过期扫描必须跳过 null
            case "FOREVER":
                return null;
            // DAYS：自<b>支付时刻</b>起 N 天。★起算点是付款而不是下单，两者可能差 15 分钟
            case "DAYS":
                if (course.getValidDays() == null || course.getValidDays() < 1) {
                    log.warn("valid_type=DAYS 但没配 valid_days，按永久的发: courseId={}", booking.getCourseId());
                    return null;
                }
                return payTime.plusDays(course.getValidDays());
            // RANGE：固定窗口（短期训练营），与付款时刻无关，只看截止
            case "RANGE":
                if (course.getUseEndTime() == null) {
                    log.warn("valid_type=RANGE 但没配 use_end_time，按永久的发: courseId={}", booking.getCourseId());
                    return null;
                }
                return course.getUseEndTime();
            default:
                log.warn("未知的 valid_type={}，按永久的发: courseId={}",
                        course.getValidType(), booking.getCourseId());
                return null;
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

        LocalDateTime now = LocalDateTime.now();
        List<EntitlementVO> result = new ArrayList<>(rows.size());
        for (Entitlement row : rows) {
            // usableOnly 在内存里过滤而不是加 SQL 条件：
            // "可用"要判次数与有效期两套 NULL 语义，写进 WHERE 会变成一条谁也读不懂的 SQL，
            // 而这里的量级（一个人几十张凭证）根本不需要数据库来帮这个筛
            if (Boolean.TRUE.equals(usableOnly) && !isBookable(row, now)) {
                continue;
            }
            result.add(toVO(row));
        }
        return result;
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
        if (!isBookable(entitlement, LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.ENTITLEMENT_UNUSABLE);
        }
        return entitlement;
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
        // ★三个条件缺一不可：status 等值（只处理还在用的）、valid_until IS NOT NULL
        //   （FOREVER 的凭证必须跳过，漏了就是常年课集体误判退款）、范围在后（命中 idx_expire）
        List<Entitlement> overdue = entitlementMapper.selectList(new LambdaQueryWrapper<Entitlement>()
                .eq(Entitlement::getStatus, EntitlementStatus.ACTIVE)
                .isNotNull(Entitlement::getValidUntil)
                .lt(Entitlement::getValidUntil, now)
                .last("LIMIT " + EXPIRE_BATCH_SIZE));
        if (overdue.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (Entitlement entitlement : overdue) {
            // ===== 第 2 步：CAS 置过期。影响 0 行 = 别的实例或上一轮已处理，跳过即可 =====
            // 改：t_entitlement.status ACTIVE→EXPIRED，带状态条件，重跑安全、多实例安全
            int rows = entitlementMapper.expireOne(entitlement.getId(), now);
            if (rows == 0) {
                continue;
            }
            processed++;

            // ===== 第 3 步：一次都没用过的，把订单推到退款（D7）=====
            if (entitlement.getUsedTimes() == null || entitlement.getUsedTimes() == 0) {
                // 改：t_booking.status PAID→REFUNDED，同样带状态条件；
                // 已退款/已取消的单影响 0 行，不会重复退
                int refunded = bookingMapper.markRefunded(entitlement.getOrderNo(), now);
                if (refunded > 0) {
                    notifyPublisher.publishBookingNotify(entitlement.getUserId(), "凭证已过期，款项已退回",
                            "「" + entitlement.getCourseName() + "」的凭证到期时一次都没用过，"
                                    + "订单 " + entitlement.getOrderNo() + " 已置为已退款。");
                    log.info("凭证过期且未使用，订单已退款: orderNo={}, entitlementId={}",
                            entitlement.getOrderNo(), entitlement.getId());
                }
            } else {
                // 用过一点的：剩余次数不退（D7 明确规则），只把凭证置终态并告知用户
                notifyPublisher.publishBookingNotify(entitlement.getUserId(), "凭证已过期",
                        "「" + entitlement.getCourseName() + "」的凭证已到期，已用 "
                                + entitlement.getUsedTimes() + " 次，剩余次数按规则不予退还。");
            }
        }

        if (processed > 0) {
            log.info("凭证过期扫描完成: 本轮处理 {} 张（上限 {}）", processed, EXPIRE_BATCH_SIZE);
        }
        return processed;
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /**
     * 凭证当下能不能拿去预约 —— <b>规则本身写在实体上</b>
     * （{@link Entitlement#usableAt}），这里只是给本类的几处调用一个短名。
     *
     * <p>★为什么不在这里再抄一遍三个条件：预约创建时要问的是"<b>上课那一刻</b>还有效吗"，
     * 列表页问的是"现在还能用吗"。两处各写一份判断，迟早会有一份漏掉 NULL 分支，
     * 而漏掉的后果是"不限次课约不了"或"常年课被当过期"这种线上事故级错误。</p>
     */
    private boolean isBookable(Entitlement entitlement, LocalDateTime moment) {
        return entitlement.usableAt(moment);
    }

    /** 实体 → C 端 VO（字段名对齐前端 Mock 的 entitlementVO，派生值全部在这里算完） */
    @Override
    public EntitlementVO toVO(Entitlement entitlement) {
        boolean unlimited = entitlement.getTotalTimes() == null;
        int used = entitlement.getUsedTimes() == null ? 0 : entitlement.getUsedTimes();
        Integer remain = unlimited ? null : Math.max(entitlement.getTotalTimes() - used, 0);

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
                .remainTimes(remain)
                .unlimited(unlimited)
                // ★永久有效给 null，不给 9999-12-31 这种魔法值：
                // 前端判空比判"是不是那个魔法日期"可靠得多
                .validUntil(entitlement.getValidUntil() == null ? null : entitlement.getValidUntil().format(TIME_FMT))
                .validUntilText(formatValidUntil(entitlement.getValidUntil()))
                .status(entitlement.getStatus())
                .statusText(EntitlementStatus.textOf(entitlement.getStatus()))
                .canBook(isBookable(entitlement, LocalDateTime.now()))
                // 同名课多张凭证靠这一行区分（买两次就有两条"燃脂跳操"），尾号 + 购买日期足够认出来
                .subTitle("订单尾号 " + tailOf(entitlement.getOrderNo())
                        + " · " + (entitlement.getCreatedAt() == null ? "" : formatDay(entitlement.getCreatedAt()) + " 购买"))
                .build();
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
}

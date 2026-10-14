package com.yuedong.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.constant.MqConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CouponClient;
import com.yuedong.course.api.client.CourseClient;
import com.yuedong.course.api.client.StoreClient;
import com.yuedong.course.api.dto.CouponUsableDTO;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.StoreDTO;
import com.yuedong.order.api.dto.BookingDTO;
import com.yuedong.order.api.dto.EntitlementAdminDTO;
import com.yuedong.order.api.dto.OrderAdminDetailDTO;
import com.yuedong.order.api.dto.ReservationAdminDTO;
import com.yuedong.order.constant.BookingStatus;
import com.yuedong.order.constant.EntitlementStatus;
import com.yuedong.order.constant.OrderConstants;
import com.yuedong.order.constant.ReservationStatus;
import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.mapper.ReservationMapper;
import com.yuedong.order.model.dto.BookingCreateDTO;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.model.entity.Entitlement;
import com.yuedong.order.model.entity.Reservation;
import com.yuedong.order.model.vo.BookingDetailVO;
import com.yuedong.order.model.vo.BookingVO;
import com.yuedong.order.model.vo.EntitlementVO;
import com.yuedong.order.mq.NotifyPublisher;
import com.yuedong.order.mq.message.OrderTimeoutMessage;
import com.yuedong.order.service.BookingService;
import com.yuedong.order.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 购买订单实现（v2）。
 *
 * <p>★与旧实现最大的区别：<b>购买不占任何名额，所以本类没有任何补偿逻辑</b>。
 * 旧 {@code create()} 里有三段补偿（占位后落库失败要释放、用券失败要释放、超时取消要释放），
 * 每一段都是"这步失败而上一步已生效怎么办"的手工修补，也正是 bug 的温床——
 * 其中释放名额的返回值当初根本没被检查。新模型下购买只写自己库的一张表，
 * 失败就是失败，不需要回滚别人。</p>
 *
 * <p>另一处刻意的删除：旧代码有"同人同课不能重复下单"的去重。那是"下单即预约"时代的规则；
 * v2 里购买是<b>买次数</b>，想多上几节课就该多下几单，去重反而挡路。
 * 顺带也消掉了"先查再插非原子、并发双击能造出两张有效单"那个隐患（文档 P1-1）——
 * 因为业务上已经允许两张单存在。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    /** 未支付订单保留时长（分钟），与延迟消息的 x-delay 保持一致 */
    private static final int UNPAID_TIMEOUT_MINUTES = 15;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BookingMapper bookingMapper;
    private final ReservationMapper reservationMapper;
    private final CourseClient courseClient;
    private final CouponClient couponClient;
    private final StoreClient storeClient;
    private final RabbitTemplate rabbitTemplate;
    private final NotifyPublisher notifyPublisher;
    private final ObjectMapper objectMapper;
    private final EntitlementService entitlementService;
    private final com.yuedong.order.service.RatingService ratingService;
    /**
     * 预约服务。<b>只用于管理端详情</b>把三条轴拼在一起。
     * <p>★不会形成循环依赖：ReservationServiceImpl 依赖的是凭证服务与 mapper，
     * 不回头依赖订单服务。如果哪天预约里要读订单服务的逻辑，
     * 就得把这层聚合挪到别处（比如 admin 侧或一个新的查询服务），而不是让两边互注。</p>
     */
    private final com.yuedong.order.service.ReservationService reservationService;

    // =====================================================================
    // 创建订单
    // =====================================================================
    @Override
    public BookingVO create(Long userId, BookingCreateDTO dto) {
        // 单买：不属于任何组
        return create(userId, dto, null);
    }

    @Override
    public BookingVO create(Long userId, BookingCreateDTO dto, String parentNo) {
        // ===== 第 1 步：查课程快照（Feign 只读，不写远端任何东西）=====
        // 一次调用同时拿到课程字段、门店名与该店的可售状态，避免"查课程 + 再查门店"两次 RPC
        Result<CourseDTO> detailResult = courseClient.detail(dto.getCourseId(), dto.getStoreId());
        if (detailResult.getCode() != 0 || detailResult.getData() == null) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }
        CourseDTO course = detailResult.getData();

        // ===== 第 2 步：形态与可售校验 =====
        // 展示型课程（CONTENT）没有"购买"这个动作；而"课程存在"不等于"能卖"——
        // 不在可售窗口内同样要拒绝，否则用户会买到一张用不了的课
        if (!"SALE".equals(course.getCourseKind())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该课程为展示内容，无需购买");
        }
        if (!Boolean.TRUE.equals(course.getOnSale())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该课程当前不可购买（未开售或已停售）");
        }

        // ===== 第 3 步：算价（用券时先校验可用性，此时还不动券）=====
        // 份数由前端传（1 份 = 1 张凭证）。★上限在凭证服务里统一判，这里只兜住非法值：
        // 负数与 0 都按 1 处理，而不是报错——"买一份"是唯一合理的退路
        int quantity = dto.getQuantity() == null || dto.getQuantity() < 1
                ? 1 : Math.min(dto.getQuantity(), OrderConstants.MAX_QUANTITY_PER_ITEM);
        BigDecimal originalAmount = course.getPrice().multiply(BigDecimal.valueOf(quantity));
        BigDecimal discountAmount = BigDecimal.ZERO;
        String couponTitle = null;
        if (dto.getCouponRecordId() != null) {
            // ★v2 用券校验要带上"买哪门课、在哪家店"：券的可用范围是 券 × 订单 的关系，
            // 只给金额判不出 scope=1/2，一张限高新店的券就能用在大学城店的订单上
            Result<CouponUsableDTO> couponResult = couponClient.info(
                    dto.getCouponRecordId(), userId, originalAmount, dto.getCourseId(), dto.getStoreId());
            CouponUsableDTO coupon = couponResult.getData();
            if (couponResult.getCode() != 0 || coupon == null || !coupon.isUsable()) {
                // 把券服务给的具体原因带回前端（未达门槛 / 已过期 / 不在适用课程内）。
                // 只说"优惠券不可用"用户无从下手
                String reason = coupon == null ? ErrorCode.COUPON_NOT_USABLE.getMessage() : coupon.getReason();
                throw new BusinessException(ErrorCode.COUPON_NOT_USABLE, reason);
            }
            couponTitle = coupon.getTitle();
            discountAmount = coupon.getDiscount();
        }
        // 实付不能为负：满 100 减 120 这类配置错误的券，宁可少收也不能倒找钱
        BigDecimal payAmount = originalAmount.subtract(discountAmount).max(BigDecimal.ZERO);

        // ===== 第 4 步：建单（一次本地 insert，无跨服务写，所以没有 try/补偿）=====
        Booking booking = new Booking();
        booking.setOrderNo(genOrderNo());
        // 组号只在购物车结算时有值（由 CartServiceImpl 传入，不来自请求体）
        booking.setParentNo(parentNo);
        booking.setUserId(userId);
        booking.setCourseId(course.getId());
        booking.setCourseKind(course.getCourseKind());
        booking.setStoreId(course.getStoreId());
        booking.setCourseName(course.getName());
        // 门店名为 null 说明课程与该店的关系没配好。★宁可给个明显异常的名字也不静默存 null：
        // 订单列表要显示门店名，null 的症状是"门店一栏空白"，回溯成本极高
        booking.setStoreName(course.getStoreName() == null ? "门店未配置" : course.getStoreName());
        booking.setLessonHours(course.getLessonHours());
        booking.setSaleType(course.getSaleType());
        // TIMES：一单给几次 = 课程的 times_per_buy（管理员配课时定的，★不是固定 1；
        // 种子数据里"私教·增肌入门"就是买一单给 2 次）。UNLIMITED 存 null 表示不限次
        booking.setTimesGranted("UNLIMITED".equals(course.getSaleType()) ? null
                : (course.getTimesPerBuy() == null || course.getTimesPerBuy() < 1 ? 1 : course.getTimesPerBuy()));
        booking.setQuantity(quantity);
        booking.setOriginalAmount(originalAmount);
        booking.setDiscountAmount(discountAmount);
        booking.setPayAmount(payAmount);
        booking.setCouponRecordId(dto.getCouponRecordId());
        booking.setCouponTitle(couponTitle);
        booking.setStatus(BookingStatus.UNPAID);
        booking.setRefunded(0);
        booking.setCreatedAt(LocalDateTime.now());
        bookingMapper.insert(booking);

        // ===== 第 5 步：核销优惠券（全流程唯一一处跨服务写）=====
        // 失败直接抛异常，本地订单随之回滚，没有远端名额需要释放。
        // ⚠️ 残留缺口要说清：若"核销请求其实成功了但响应丢失"，会出现券已用而订单不存在。
        //   真实项目要靠 outbox 表或对账任务修；本项目不引入那套机制，但必须能答出这里的风险。
        if (dto.getCouponRecordId() != null) {
            Result<Boolean> useResult = couponClient.use(dto.getCouponRecordId(), userId, booking.getOrderNo());
            if (useResult.getCode() != 0 || !Boolean.TRUE.equals(useResult.getData())) {
                log.error("优惠券核销失败，下单终止: userId={}, recordId={}, orderNo={}",
                        userId, dto.getCouponRecordId(), booking.getOrderNo());
                throw new BusinessException(ErrorCode.COUPON_NOT_USABLE, "优惠券核销失败，请重试");
            }
        }

        // ===== 第 6 步：15 分钟未支付自动取消的延迟消息 =====
        sendTimeoutMessage(booking.getOrderNo(), userId);

        // ===== 第 7 步：站内通知 =====
        notifyPublisher.publishBookingNotify(userId, "订单创建成功",
                "已为你生成「" + course.getName() + "」订单，实付 ¥" + payAmount
                        + (couponTitle == null ? "" : "（已用" + couponTitle + "）")
                        + "，请在 " + UNPAID_TIMEOUT_MINUTES + " 分钟内完成支付，超时将自动取消。");

        log.info("购买订单已创建: orderNo={}, userId={}, courseId={}, storeId={}, 份数={}, payAmount={}",
                booking.getOrderNo(), userId, course.getId(), course.getStoreId(), quantity, payAmount);
        // 新建的单一定是 UNPAID，凭证要等付款后才出，所以这里传空列表（actions 只会给 PAY/CANCEL）
        return toVO(booking, new ArrayList<>());
    }

    // =====================================================================
    // 支付与取消
    // =====================================================================
    /**
     * 模拟支付：CAS 流转状态 + <b>同事务出票</b>。
     *
     * <p>★为什么必须同事务：钱和权益是一件事的两面。分两个事务就会出现
     * "钱已扣但凭证没生成"（用户付了钱约不了课）或"凭证生成了但钱没扣"（白送课）——
     * 两者都要靠对账才发现。这里不需要分布式事务：订单与凭证<b>同在 yd_order 一个库</b>。</p>
     *
     * <p>★整组支付用一条 UPDATE 而不是循环调单笔：循环里第 3 张失败时前 2 张已提交，
     * 用户会遇到"付了一部分"这种说不清的状态。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pay(Long userId, String orderNo, String parentNo) {
        boolean groupPay = parentNo != null && !parentNo.isBlank();
        if (!groupPay && (orderNo == null || orderNo.isBlank())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少订单号");
        }
        LocalDateTime now = LocalDateTime.now();

        List<Booking> paidOrders = new ArrayList<>();
        if (groupPay) {
            // ① 整组 CAS：同组所有 UNPAID 一起变 PAID。影响 0 行 = 这组已经不待支付
            if (bookingMapper.markGroupPaid(parentNo, userId, now) == 0) {
                throw new BusinessException(ErrorCode.BOOKING_STATUS_ERROR);
            }
            // ② 取回这组里已支付的单（可能有些早就付过了，只处理现在是 PAID 的）
            paidOrders = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                    .eq(Booking::getParentNo, parentNo)
                    .eq(Booking::getUserId, userId)
                    .eq(Booking::getStatus, BookingStatus.PAID));
        } else {
            // ① 单笔 CAS。影响 0 行 = 状态不是 UNPAID（重复支付、或已被超时任务取消）
            if (bookingMapper.markPaid(orderNo, userId, now) == 0) {
                throw new BusinessException(ErrorCode.BOOKING_STATUS_ERROR);
            }
            paidOrders.add(loadOwned(userId, orderNo));
        }

        // ===== ③ 逐单出票：份数 = quantity，多份不合并（D4）=====
        int granted = 0;
        for (Booking booking : paidOrders) {
            granted += entitlementService.grantForPaidOrder(booking).size();
        }
        log.info("支付成功: userId={}, 模式={}, 单数={}, 出票 {} 张", userId,
                groupPay ? "整组 " + parentNo : "单笔 " + orderNo, paidOrders.size(), granted);

        // ===== ④ 站内通知（一次给全组，别每张单发一条刷屏）=====
        String firstName = paidOrders.get(0).getCourseName();
        notifyPublisher.publishBookingNotify(userId, "支付成功，凭证已生效",
                "「" + firstName + "」" + (paidOrders.size() > 1 ? "等 " + paidOrders.size() + " 笔订单" : "")
                        + "已支付，共生成 " + granted + " 张凭证。"
                        + "凭证只能在购买门店约教练，在「我的凭证」里选一张就能去约时间。");
    }

    @Override
    public void cancel(Long userId, String orderNo, String parentNo) {
        if (parentNo != null && !parentNo.isBlank()) {
            cancelGroup(userId, parentNo);
            return;
        }
        if (orderNo == null || orderNo.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少订单号");
        }
        Booking booking = loadOwned(userId, orderNo);

        // ===== 第 1 步：CAS 取消。已支付的单不走取消（走退款），所以影响 0 行就报错 =====
        if (bookingMapper.cancel(orderNo, userId, "用户主动取消", LocalDateTime.now()) == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_ERROR, "该订单已支付或已取消，无法取消");
        }

        // 注意：<b>没有释放名额这一步了</b>。购买不占名额，时段占用发生在创建预约时，
        // 由 t_coach_busy 的唯一索引与删除来管理，订单这边无资源可回滚。
        refundCouponIfNeeded(booking, orderNo);

        notifyPublisher.publishBookingNotify(userId, "订单已取消",
                "「" + booking.getCourseName() + "」的订单已取消"
                        + (booking.getCouponRecordId() != null ? "，使用的优惠券已退回。" : "。"));
        log.info("订单已取消: orderNo={}, userId={}", orderNo, userId);
    }

    /**
     * 整组取消：购物车合并下单出的 N 张待支付单一起取消。
     * <p>★一组单要么整体付、要么整体撤。让用户在收银台上看到
     * "3 张里取消了 2 张"是最糟糕的状态——他接下来不知道该付哪一张。</p>
     */
    private void cancelGroup(Long userId, String parentNo) {
        // 先取回这一组的单，退券与通知都要逐张处理（★退券必须在状态改完之后）
        List<Booking> group = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getParentNo, parentNo)
                .eq(Booking::getUserId, userId));
        if (group.isEmpty()) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }
        if (bookingMapper.cancelGroup(parentNo, userId, "用户主动取消", LocalDateTime.now()) == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_ERROR, "该组订单已支付或已取消，无法取消");
        }

        int couponReturned = 0;
        for (Booking booking : group) {
            if (booking.getCouponRecordId() != null) {
                refundCouponIfNeeded(booking, booking.getOrderNo());
                couponReturned++;
            }
        }
        notifyPublisher.publishBookingNotify(userId, "订单已取消",
                "这一组 " + group.size() + " 笔订单已取消"
                        + (couponReturned > 0 ? "，使用的 " + couponReturned + " 张优惠券已退回。" : "。"));
        log.info("整组订单已取消: parentNo={}, userId={}, 单数={}", parentNo, userId, group.size());
    }

    /**
     * 退券。★旧实现连远端返回值都不检查，券没退成也当成功，用户下次发现券"已使用"却找不到订单。
     *
     * <p>此刻订单已经是 CANCELED（已提交），不能因为退券失败再抛异常给前端——
     * 那会表现为"取消失败"，用户反复点更糟。所以只记 error 留痕，由人工或对账处理。</p>
     */
    private void refundCouponIfNeeded(Booking booking, String orderNo) {
        if (booking.getCouponRecordId() == null) {
            return;
        }
        try {
            Result<Boolean> refundResult = couponClient.refund(booking.getCouponRecordId());
            if (refundResult.getCode() != 0 || !Boolean.TRUE.equals(refundResult.getData())) {
                log.error("退券未成功，需人工核对: orderNo={}, couponRecordId={}",
                        orderNo, booking.getCouponRecordId());
            }
        } catch (Exception e) {
            log.error("退券调用异常: orderNo={}, couponRecordId={}",
                    orderNo, booking.getCouponRecordId(), e);
        }
    }

    // =====================================================================
    // 查询
    // =====================================================================
    @Override
    public List<BookingVO> list(Long userId, String tab) {
        String normalized = tab == null || tab.isBlank() ? "ALL" : tab.trim().toUpperCase();

        // ===== 第 1 步：先用订单轴能表达的条件收窄 =====
        // WHERE user_id=? [+ status]，命中 idx_user_status(user_id, status, id)
        LambdaQueryWrapper<Booking> wrapper = new LambdaQueryWrapper<Booking>()
                .eq(Booking::getUserId, userId);
        switch (normalized) {
            case "UNPAID", "PAID", "CANCELED", "REFUNDED" -> wrapper.eq(Booking::getStatus, normalized);
            // 待使用 / 已预约 / 已完成 / 待评价 都以"已支付"为前提，先按 PAID 收
            case "WAIT_USE", "BOOKED", "FINISHED", "TO_RATE" -> wrapper.eq(Booking::getStatus, BookingStatus.PAID);
            // ★"已过期/退款"这个 tab 天生跨两轴：订单 REFUNDED ∪ 凭证 EXPIRED 的订单，
            //   两个来源要合并进同一个列表，所以这里只能取 PAID 与 REFUNDED 两类再后筛
            case "EXPIRED" -> wrapper.in(Booking::getStatus, BookingStatus.PAID, BookingStatus.REFUNDED);
            default -> { }   // ALL：不加状态条件
        }
        wrapper.orderByDesc(Booking::getId);

        List<Booking> bookings = bookingMapper.selectList(wrapper);
        if (bookings.isEmpty()) {
            return new ArrayList<>();
        }

        // ===== 第 2 步：批量取凭证与预约，一次查完（★不在循环里逐单查）=====
        List<String> orderNos = new ArrayList<>(bookings.size());
        for (Booking booking : bookings) {
            orderNos.add(booking.getOrderNo());
        }
        Map<String, List<Entitlement>> entitlementMap =
                entitlementService.mapByOrderNos(userId, orderNos);
        Set<String> bookedOrderNos = loadBookedOrderNos(userId, orderNos);
        // 只有"待评价"这个 tab 需要知道哪些单评过，别的情况不去多问一次
        Set<String> ratedOrderNos = "TO_RATE".equals(normalized)
                ? ratingService.ratedOrderNos(userId, orderNos) : new HashSet<>();

        // ===== 第 3 步：按 tab 做跨轴后筛 =====
        LocalDateTime now = LocalDateTime.now();
        List<BookingVO> result = new ArrayList<>(bookings.size());
        for (Booking booking : bookings) {
            List<Entitlement> entitlements =
                    entitlementMap.getOrDefault(booking.getOrderNo(), new ArrayList<>());

            if (!matchTab(normalized, booking, entitlements, bookedOrderNos, ratedOrderNos)) {
                continue;
            }
            result.add(toVO(booking, entitlements));
        }
        return result;
    }

    /**
     * tab 的跨轴判断。
     * <p>★这一段是整个列表接口里唯一"不简单"的地方，但它是<b>模型换来的必要复杂度</b>：
     * 三条轴各自的状态无法用订单表的某一个列表达。
     * 反面教材就是旧实现——把三个语义塞进一个 status 列，于是"取消要不要退名额"
     * 要看当前状态、"核销"也要改状态，规则绕成一团。</p>
     */
    private boolean matchTab(String tab, Booking booking, List<Entitlement> entitlements,
                             Set<String> bookedOrderNos, Set<String> ratedOrderNos) {
        switch (tab) {
            case "WAIT_USE":
                return hasUsable(entitlements, LocalDateTime.now());
            case "BOOKED":
                return bookedOrderNos.contains(booking.getOrderNo());
            case "FINISHED":
                return allSettled(entitlements);
            case "TO_RATE":
                // ★口径（已确认）：凭证全部用完或过期之后才可评，且这单还没评过。
                // 少掉后半个条件，"待评价"会把评过的单一直挂在角标上，数字永远降不下去
                return allSettled(entitlements) && !ratedOrderNos.contains(booking.getOrderNo());
            case "EXPIRED":
                // 两个来源：订单被退款了，或者凭证里有已过期的
                if (BookingStatus.REFUNDED.equals(booking.getStatus())) {
                    return true;
                }
                for (Entitlement entitlement : entitlements) {
                    if (EntitlementStatus.EXPIRED.equals(entitlement.getStatus())) {
                        return true;
                    }
                }
                return false;
            default:
                return true;
        }
    }

    private boolean hasUsable(List<Entitlement> entitlements, LocalDateTime now) {
        for (Entitlement entitlement : entitlements) {
            if (entitlement.usableAt(now)) {
                return true;
            }
        }
        return false;
    }

    /** 这张订单的凭证是否都已结束（用完或过期）。★一张凭证都没有不算"已完成" */
    private boolean allSettled(List<Entitlement> entitlements) {
        if (entitlements.isEmpty()) {
            return false;
        }
        for (Entitlement entitlement : entitlements) {
            if (EntitlementStatus.ACTIVE.equals(entitlement.getStatus())) {
                return false;
            }
        }
        return true;
    }

    /** 一次查回"这些订单里哪些还有待上课的预约"，返回订单号集合 */
    private Set<String> loadBookedOrderNos(Long userId, List<String> orderNos) {
        List<Reservation> rows = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getStatus, ReservationStatus.BOOKED)
                .in(Reservation::getOrderNo, orderNos));
        Set<String> orderSet = new java.util.HashSet<>();
        for (Reservation row : rows) {
            orderSet.add(row.getOrderNo());
        }
        return orderSet;
    }

    /**
     * 订单详情：把"订单 → 凭证 → 预约"三层一次给全。
     *
     * <p>★这个接口是三条状态轴拆开的<b>可见收益</b>：用户在一个页面上能看到
     * "买了 3 份、每张几次、约了哪节、上完了没"。
     * 旧模型里订单一件事就是预约一件事，这三层的语义全挤在一个 status 上，
     * 详情页只能显示一句"已完成"，用户不知道已完成的是钱还是课。</p>
     */
    @Override
    public BookingDetailVO detail(Long userId, String orderNo) {
        Booking booking = loadOwned(userId, orderNo);

        // ===== 第 1 步：这张单的凭证 =====
        List<Entitlement> entitlements = entitlementService
                .mapByOrderNos(userId, List.of(orderNo))
                .getOrDefault(orderNo, new ArrayList<>());
        List<EntitlementVO> entitlementVOs = new ArrayList<>(entitlements.size());
        List<Long> entitlementIds = new ArrayList<>(entitlements.size());
        for (Entitlement entitlement : entitlements) {
            entitlementVOs.add(entitlementService.toVO(entitlement));
            entitlementIds.add(entitlement.getId());
        }

        // ===== 第 2 步：这些凭证名下的预约记录（一次 IN 查完，挂在各自凭证下面）=====
        if (!entitlementIds.isEmpty()) {
            List<Reservation> reservations = reservationMapper.selectList(
                    new LambdaQueryWrapper<Reservation>()
                            .eq(Reservation::getUserId, userId)
                            .in(Reservation::getEntitlementId, entitlementIds)
                            .orderByDesc(Reservation::getStartTime));
            Map<Long, List<EntitlementVO.ReservationBrief>> briefsByEntitlement = new java.util.HashMap<>();
            for (Reservation reservation : reservations) {
                EntitlementVO.ReservationBrief brief = EntitlementVO.ReservationBrief.builder()
                        .reservationNo(reservation.getReservationNo())
                        .startText(formatTime(reservation.getStartTime()))
                        .coachName(reservation.getCoachName())
                        .status(reservation.getStatus())
                        .statusText(ReservationStatus.textOf(reservation.getStatus()))
                        .build();
                briefsByEntitlement.computeIfAbsent(reservation.getEntitlementId(),
                        key -> new ArrayList<>()).add(brief);
            }
            for (EntitlementVO vo : entitlementVOs) {
                vo.setReservations(briefsByEntitlement.getOrDefault(vo.getEntitlementId(), new ArrayList<>()));
            }
        }

        // ===== 第 3 步：两个装饰性字段各一次 Feign（详情页低频，可接受；取不到不影响主信息）=====
        String courseType = null;
        String coverColor = null;
        String storeAddress = null;
        try {
            CourseDTO course = courseClient.detail(booking.getCourseId(), booking.getStoreId()).getData();
            if (course != null) {
                courseType = course.getType();
                coverColor = course.getCoverColor();
            }
            StoreDTO store = storeClient.detail(booking.getStoreId()).getData();
            if (store != null) {
                storeAddress = store.getAddress();
            }
        } catch (Exception e) {
            // ★课程服务抖一下不该让用户看不到自己的订单：地址和配色是补充信息，缺了就少显示一行
            log.warn("订单详情的补充信息取数失败，继续返回主体: orderNo={}", orderNo, e);
        }

        LocalDateTime now = LocalDateTime.now();
        // 详情页要显示"评过没有"，一次单条查询即可（这张单的 rating 最多一条）
        boolean rated = !ratingService
                .ratedOrderNos(userId, List.of(orderNo)).isEmpty();
        List<String> actions = new ArrayList<>();
        if (BookingStatus.UNPAID.equals(booking.getStatus())) {
            actions.add("PAY");
            actions.add("CANCEL");
        } else if (BookingStatus.PAID.equals(booking.getStatus())) {
            if (hasUsable(entitlements, now)) {
                actions.add("BOOK");
            }
            // ★RATE 两个条件：凭证全部结束 <b>且</b> 还没评过。
            // 评过的单再给一个 RATE 按钮，点进去提交只会得到"本单已评价过"
            if (allSettled(entitlements) && !rated) {
                actions.add("RATE");
            }
            actions.add("AGAIN");
        } else {
            actions.add("AGAIN");
        }

        return BookingDetailVO.builder()
                .orderNo(booking.getOrderNo())
                .parentNo(booking.getParentNo())
                .isFromCart(booking.getParentNo() != null)
                .status(booking.getStatus())
                .statusText(BookingStatus.textOf(booking.getStatus()))
                .courseId(booking.getCourseId())
                .courseName(booking.getCourseName())
                .courseKind(booking.getCourseKind())
                .courseType(courseType)
                .coverColor(coverColor)
                .lessonHours(booking.getLessonHours())
                .saleType(booking.getSaleType())
                .saleTypeText("UNLIMITED".equals(booking.getSaleType()) ? "不限次"
                        : ("CONTENT".equals(booking.getCourseKind()) ? null : "按次购买"))
                .timesGranted(booking.getTimesGranted())
                .quantity(booking.getQuantity())
                .storeId(booking.getStoreId())
                .storeName(booking.getStoreName())
                .storeAddress(storeAddress)
                .unitPrice(booking.getQuantity() == null || booking.getQuantity() < 1
                        ? booking.getOriginalAmount()
                        : booking.getOriginalAmount().divide(BigDecimal.valueOf(booking.getQuantity()), 2,
                        java.math.RoundingMode.HALF_UP))
                .unitPriceText(formatMoney(booking.getOriginalAmount()))
                .originalAmount(booking.getOriginalAmount())
                .originalAmountText(formatMoney(booking.getOriginalAmount()))
                .discountAmount(booking.getDiscountAmount())
                .discountAmountText(formatMoney(booking.getDiscountAmount()))
                .payAmount(booking.getPayAmount())
                .payAmountText(formatMoney(booking.getPayAmount()))
                .couponTitle(booking.getCouponTitle())
                .createdText(formatTime(booking.getCreatedAt()))
                .paidText(formatTime(booking.getPayTime()))
                .canceledText(formatTime(booking.getCanceledAt()))
                .refundText(formatTime(booking.getRefundAt()))
                .cancelReason(booking.getCancelReason())
                .entitlements(entitlementVOs)
                .rated(rated)
                .actions(actions)
                .build();
    }

    @Override
    public PageDTO<BookingDTO> adminPage(String status, String keyword, Integer page, Integer size) {
        // 分页查订单：MP 的 PaginationInnerInterceptor 自动补 LIMIT 与 COUNT(*)
        Page<Booking> result = bookingMapper.selectPage(
                new Page<>(page == null ? 1 : page, size == null ? 10 : size),
                new LambdaQueryWrapper<Booking>()
                        .eq(status != null && !status.isBlank() && !"ALL".equals(status),
                                Booking::getStatus, status)
                        .like(keyword != null && !keyword.isBlank(), Booking::getOrderNo, keyword)
                        .orderByDesc(Booking::getId));

        List<BookingDTO> rows = new ArrayList<>();
        for (Booking booking : result.getRecords()) {
            rows.add(toDTO(booking));
        }
        return PageDTO.of(rows, result.getTotal());
    }

    @Override
    public void adminCancel(String orderNo) {
        Booking booking = bookingMapper.selectOne(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getOrderNo, orderNo));
        if (booking == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }
        // 复用用户侧的取消逻辑：状态条件、退券、通知三件事不重复实现一遍。
        // ★管理端只按单号撤这一笔（代客操作要精确，不该顺手把同组别的单也撤了）
        cancel(booking.getUserId(), orderNo, null);
        log.info("管理端代客取消订单: orderNo={}, 对象 userId={}", orderNo, booking.getUserId());
    }

    @Override
    public OrderAdminDetailDTO adminDetail(String orderNo) {
        // ===== 第 1 步：订单本体（按单号查，管理端不限定 user_id）=====
        Booking booking = bookingMapper.selectOne(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getOrderNo, orderNo));
        if (booking == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }

        // ===== 第 2 步：同一库里的另外两条轴，各一次等值查询 =====
        // ★不在循环里按凭证逐条查预约：凭证有几张就查几次，是典型的 N+1。
        //   预约表本来就存了 order_no 冗余列（就是为了这一句省一次 JOIN）
        List<EntitlementAdminDTO> entitlements = new ArrayList<>();
        Map<String, List<Entitlement>> byOrder =
                entitlementService.mapByOrderNos(booking.getUserId(), List.of(orderNo));
        for (Entitlement entitlement : byOrder.getOrDefault(orderNo, List.of())) {
            entitlements.add(entitlementService.toAdminDTO(entitlement));
        }
        entitlementService.fillReservationCounts(entitlements);

        List<ReservationAdminDTO> reservations = new ArrayList<>();
        for (Reservation reservation : reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getOrderNo, orderNo).orderByDesc(Reservation::getId))) {
            reservations.add(reservationService.toAdminDTO(reservation));
        }

        return OrderAdminDetailDTO.builder()
                .order(toDTO(booking))
                .entitlements(entitlements)
                .reservations(reservations)
                .build();
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /** 按订单号 + 用户取单。★必须带 user_id：只按 orderNo 查就成了"猜到单号就能取消别人的单" */
    private Booking loadOwned(Long userId, String orderNo) {
        Booking booking = bookingMapper.selectOne(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getOrderNo, orderNo)
                .eq(Booking::getUserId, userId));
        if (booking == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }
        return booking;
    }

    /**
     * 发 15 分钟延迟消息（交换机类型 x-delayed-message，需要 RabbitMQ 延迟插件）。
     *
     * <p>★不追求"准时"：消费端靠状态判断决定是否真取消（见
     * {@code BookingMapper#cancelIfUnpaid}），消息晚到几秒完全无害。
     * 这也是本项目不需要"延迟消息撤回"能力的原因——<b>用状态判等比撤回简单且不会错</b>。</p>
     */
    private void sendTimeoutMessage(String orderNo, Long userId) {
        try {
            OrderTimeoutMessage message = new OrderTimeoutMessage(orderNo, userId);
            int delayMillis = UNPAID_TIMEOUT_MINUTES * 60 * 1000;
            rabbitTemplate.convertAndSend(
                    MqConstants.ORDER_DELAY_EXCHANGE,
                    MqConstants.ORDER_TIMEOUT_KEY,
                    objectMapper.writeValueAsString(message),
                    msg -> {
                        msg.getMessageProperties().setDelay(delayMillis);
                        // 持久化：RabbitMQ 重启后待取消的单不会丢
                        msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return msg;
                    });
        } catch (Exception e) {
            // 消息没发出去的后果只是"这一单不会自动取消"：钱没扣、资源没占，用户可手动取消，
            // 所以记 error 而不阻断下单。真实项目要配一个"扫 UNPAID 超时单"的兜底任务
            log.error("延迟取消消息发送失败，该订单不会被自动取消: orderNo={}", orderNo, e);
        }
    }

    /**
     * 生成订单号：B + 毫秒时间戳 + 3 位随机。
     *
     * <p>★仍是弱实现：同一毫秒且随机数撞车时会被 {@code uk_order_no} 拒掉，而这里不重试。
     * 真实项目该用雪花算法（时间戳 + 机器号 + 序列号）。当前演示量级够用，
     * 但被问到"订单号怎么生成、有什么缺陷"时要能自己说出这个短板。</p>
     */
    private String genOrderNo() {
        return "B" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(100, 1000);
    }

    /**
     * 实体 → C 端 VO。
     *
     * @param entitlements 该单的凭证列表（调用方批量取好传进来，本方法不查库）
     */
    private BookingVO toVO(Booking booking, List<Entitlement> entitlements) {
        LocalDateTime now = LocalDateTime.now();
        List<String> actions = new ArrayList<>();
        StringBuilder entitlementText = new StringBuilder();

        if (BookingStatus.UNPAID.equals(booking.getStatus())) {
            // 待支付：能付、能撤，没有别的事可做
            actions.add("PAY");
            actions.add("CANCEL");
        } else if (BookingStatus.PAID.equals(booking.getStatus())) {
            // 凭证摘要："2 张凭证 · 可用 1 张"。可用与否的规则在实体上（三处 NULL 判断），
            // 这里不另写一份，免得和预约创建时的校验漂移
            int usable = 0;
            for (Entitlement entitlement : entitlements) {
                if (entitlement.usableAt(now)) {
                    usable++;
                }
            }
            entitlementText.append(entitlements.size()).append(" 张凭证 · 可用 ")
                    .append(usable).append(" 张");
            if (usable > 0) {
                actions.add("BOOK");
            }
            // ★RATE（去评价）要再查 t_rating，在评价分支接入后补，
            // 现在给出去会做出一个"点了说不能评"的按钮
            actions.add("AGAIN");
        } else {
            // 已取消 / 已退款：只剩"再来一单"
            actions.add("AGAIN");
        }

        return BookingVO.builder()
                .orderNo(booking.getOrderNo())
                .parentNo(booking.getParentNo())
                .courseId(booking.getCourseId())
                .courseName(booking.getCourseName())
                .courseKind(booking.getCourseKind())
                .storeId(booking.getStoreId())
                .storeName(booking.getStoreName())
                .lessonHours(booking.getLessonHours())
                .saleType(booking.getSaleType())
                .timesGranted(booking.getTimesGranted())
                .quantity(booking.getQuantity())
                .originalAmount(booking.getOriginalAmount())
                .discountAmount(booking.getDiscountAmount())
                .payAmount(booking.getPayAmount())
                .payAmountText(formatMoney(booking.getPayAmount()))
                .originalAmountText(formatMoney(booking.getOriginalAmount()))
                .discountAmountText(formatMoney(booking.getDiscountAmount()))
                .couponTitle(booking.getCouponTitle())
                .status(booking.getStatus())
                .statusText(BookingStatus.textOf(booking.getStatus()))
                .fromSeckill(booking.getActivityId() != null)
                .createdText(formatTime(booking.getCreatedAt()))
                .paidText(formatTime(booking.getPayTime()))
                .refundText(formatTime(booking.getRefundAt()))
                .cancelReason(booking.getCancelReason())
                .entitlementText(entitlementText.toString())
                .subTitle(booking.getParentNo() == null ? "" : "购物车合并下单")
                .actions(actions)
                .build();
    }

    /** 实体 → 管理端 DTO（跨服务给 admin-server，含 userId 供聚合会员昵称） */
    private BookingDTO toDTO(Booking booking) {
        return BookingDTO.builder()
                .orderNo(booking.getOrderNo())
                .userId(booking.getUserId())
                .courseId(booking.getCourseId())
                .courseName(booking.getCourseName())
                .storeId(booking.getStoreId())
                .storeName(booking.getStoreName())
                .lessonHours(booking.getLessonHours())
                .saleType(booking.getSaleType())
                .timesGranted(booking.getTimesGranted())
                .quantity(booking.getQuantity())
                .originalAmount(booking.getOriginalAmount())
                .discountAmount(booking.getDiscountAmount())
                .payAmount(booking.getPayAmount())
                .couponTitle(booking.getCouponTitle())
                .status(booking.getStatus())
                .statusText(BookingStatus.textOf(booking.getStatus()))
                .fromSeckill(booking.getActivityId() != null)
                .createdAt(booking.getCreatedAt())
                .payTime(booking.getPayTime())
                .cancelReason(booking.getCancelReason())
                .build();
    }

    private String formatMoney(BigDecimal amount) {
        return amount == null ? null : String.format("¥%.2f", amount);
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FMT);
    }
}

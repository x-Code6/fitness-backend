package com.yuedong.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.constant.MqConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.constant.RedisKeyConstants;
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
import com.yuedong.order.model.vo.BookingPreviewVO;
import com.yuedong.order.model.vo.CouponOptionVO;
import com.yuedong.order.model.vo.BookingVO;
import com.yuedong.order.model.vo.EntitlementVO;
import com.yuedong.order.mq.NotifyPublisher;
import com.yuedong.order.mq.message.OrderTimeoutMessage;
import com.yuedong.order.service.BookingPriceCalculator;
import com.yuedong.order.service.BookingService;
import com.yuedong.order.service.BookingTxWriter;
import com.yuedong.order.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
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
     * 算价。★建单和试算必须走它，见 {@link BookingPriceCalculator} 的类注释——
     * 两个入口各算一遍是"页面显示 100、成交按 90"这类怪事的源头。
     */
    private final BookingPriceCalculator priceCalculator;
    /**
     * 建单的事务段（写订单 + 核销券）。
     * <p>★放在单独的 bean 里是因为 Spring 事务靠代理生效，
     * 同类里 {@code this.} 自调用不走代理，注解等于白写。</p>
     */
    private final BookingTxWriter bookingTxWriter;
    /**
     * Redis：只用来存下单令牌（一次性，10 分钟）。
     * <p>★它不是"缓存"，是<b>正确性依赖</b>：Redis 挂了发不出令牌、下不了单。
     * 本项目接受这个耦合——登录令牌、秒杀库存、幂等窗口本来就都在 Redis 上，
     * 它挂了就整体不可用，不会只有下单这一条链路悄悄降级。</p>
     */
    private final StringRedisTemplate redis;
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

        // ===== 第 0 步：原子消费下单令牌（必须排在所有业务动作之前）=====
        // getAndDelete 就是一条 Redis 命令（GETDEL）：取值 + 删除不可分割，
        // 所以两个并发请求里只有一个能拿到东西，另一个拿到 null。
        // ★不能写成 exists→get→delete 三步，那样两个请求会同时看到"有"，令牌就白发了。
        // ★这一步放在算价前面：否则两个重复请求都会先跑完"查课程 + 判券"两次跨服务调用
        //   才在令牌上分出胜负，白烧一轮资源，而且窗口更长、更容易两个都跑到底。
        String tokenKey = RedisKeyConstants.orderCreateToken(userId, dto.getOrderNo());
        String issuedAt = redis.opsForValue().getAndDelete(tokenKey);
        if (issuedAt == null) {
            // 三种情况都会走到这里：① 同一个号提交第二次（手抖/前端重发）
            // ② 令牌超过 10 分钟没提交，已经自己过期 ③ 号不是这个用户的（别人的、或乱造的）
            log.warn("下单令牌无效: userId={}, orderNo={}", userId, dto.getOrderNo());
            throw new BusinessException(ErrorCode.ORDER_TOKEN_INVALID);
        }

        // ===== 第 1 步：算价（★和 /booking/preview 调的是同一个方法）=====
        // 课程快照、可售判定、份数上限、券可用性、四个金额全部由 quote 一次给全。
        // 这里 withCouponList 传 false：券已经定了，不必再拉一整份券列表浪费一次跨服务调用。
        BookingPriceCalculator.Quote quote = priceCalculator.quote(
                userId, dto.getCourseId(), dto.getStoreId(), dto.getQuantity(),
                dto.getCouponRecordId(), false);
        CourseDTO course = quote.getCourse();

        // 券不可用要在建单这里才拦。同一个"不可用"在两个入口的语义不同：
        // preview 时它是给用户看的一句说明（"还差 50 元可用"），
        // create 时它是不能继续的理由
        if (dto.getCouponRecordId() != null && !quote.isCouponUsable()) {
            String reason = quote.getCouponReason() == null
                    ? ErrorCode.COUPON_NOT_USABLE.getMessage() : quote.getCouponReason();
            throw new BusinessException(ErrorCode.COUPON_NOT_USABLE, reason);
        }

        // ===== 第 2 步：组装订单（金额和快照一律来自 quote，绝不回读前端传的值）=====
        Booking booking = new Booking();
        // ★用 preview 时就发给前端的那个号，不再在这里现生成：
        //   号在 Redis 里已经和这个用户绑过一次了，复用它才能让"一次性"这件事成立。
        booking.setOrderNo(dto.getOrderNo());
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
        booking.setTimesGranted(quote.getTimesGranted());
        booking.setQuantity(quote.getQuantity());
        booking.setOriginalAmount(quote.getOriginalAmount());
        booking.setDiscountAmount(quote.getDiscountAmount());
        booking.setPayAmount(quote.getPayAmount());
        booking.setCouponRecordId(quote.getCouponRecordId());
        booking.setCouponTitle(quote.getCouponTitle());
        booking.setStatus(BookingStatus.UNPAID);
        booking.setRefunded(0);
        booking.setCreatedAt(LocalDateTime.now());

        // ===== 第 3 步：落库 + 核销券，一个事务 =====
        // ★单独走 BookingTxWriter 这个 bean：同类自调用不走代理，事务注解会失效。
        //   券核销失败时这条 insert 一起回滚，库里不会留下"引用未核销券"的孤儿单。
        bookingTxWriter.write(booking, userId, dto.getCouponRecordId());

        // ===== 第 4 步：事务已经提交，才发不可撤回的两件事 =====
        // 15 分钟未支付自动取消的延迟消息
        sendTimeoutMessage(booking.getOrderNo(), userId);

        // 站内通知
        notifyPublisher.publishBookingNotify(userId, "订单创建成功",
                "已为你生成「" + course.getName() + "」订单，实付 ¥" + quote.getPayAmount()
                        + (quote.getCouponTitle() == null ? "" : "（已用" + quote.getCouponTitle() + "）")
                        + "，请在 " + UNPAID_TIMEOUT_MINUTES + " 分钟内完成支付，超时将自动取消。");

        log.info("购买订单已创建: orderNo={}, userId={}, courseId={}, storeId={}, 份数={}, payAmount={}",
                booking.getOrderNo(), userId, course.getId(), course.getStoreId(),
                quote.getQuantity(), quote.getPayAmount());
        // 新建的单一定是 UNPAID，凭证要等付款后才出，所以这里传空列表（actions 只会给 PAY/CANCEL）
        return toVO(booking, new ArrayList<>());
    }

    // =====================================================================
    // 下单页试算
    // =====================================================================
    @Override
    public BookingPreviewVO preview(Long userId, Long courseId, Long storeId,
                                    Integer quantity, Long couponRecordId) {

        // ★与 create 同一个算价方法，差别只有 withCouponList=true（要顺带把券列表带回去）
        BookingPriceCalculator.Quote quote =
                priceCalculator.quote(userId, courseId, storeId, quantity, couponRecordId, true);
        CourseDTO course = quote.getCourse();

        List<CouponOptionVO> coupons = new ArrayList<>(quote.getCouponOptions().size());
        for (CouponUsableDTO option : quote.getCouponOptions()) {
            coupons.add(CouponOptionVO.builder()
                    .recordId(option.getRecordId())
                    .couponId(option.getCouponId())
                    .title(option.getTitle())
                    .couponType(option.getCouponType())
                    .threshold(option.getThreshold())
                    .thresholdText(thresholdText(option.getThreshold()))
                    .discount(option.getDiscount())
                    .discountText(option.getDiscount() == null ? null : "-¥" + plain(option.getDiscount()))
                    .usable(option.isUsable())
                    .reason(option.getReason())
                    .build());
        }

        // ===== 第 3 步：算完价一切正常，才发这一枚下单令牌 =====
        // ★顺序有意义：可售校验、份数上限、算价都在前面，
        //   这样"给一门根本买不了的课发号"这种事不会发生，Redis 里也不会堆废令牌。
        // ★每次 preview 都发新号（不发同一个号第二次）：用户改份数、换券都拿到新号，
        //   旧号让它自己 10 分钟过期。不做"重复 preview 复用同一个号"那种优化——
        //   复用就要引入"号已经用掉了还能再发"的状态，一次性这件事就不干净了。
        String orderNo = genOrderNo();
        redis.opsForValue().set(RedisKeyConstants.orderCreateToken(userId, orderNo),
                String.valueOf(System.currentTimeMillis()),
                Duration.ofMinutes(OrderConstants.ORDER_TOKEN_EXPIRE_MINUTES));

        return BookingPreviewVO.builder()
                .orderNo(orderNo)
                .courseId(course.getId())
                .courseName(course.getName())
                .storeId(course.getStoreId())
                .storeName(course.getStoreName() == null ? "门店未配置" : course.getStoreName())
                .lessonHours(course.getLessonHours())
                .saleType(course.getSaleType())
                .timesGranted(quote.getTimesGranted())
                .quantity(quote.getQuantity())
                .maxQuantity(OrderConstants.MAX_QUANTITY_PER_ITEM)
                .unitPrice(quote.getUnitPrice())
                .unitPriceText(formatMoney(quote.getUnitPrice()))
                .originalAmount(quote.getOriginalAmount())
                .originalAmountText(formatMoney(quote.getOriginalAmount()))
                .discountAmount(quote.getDiscountAmount())
                .discountAmountText(formatMoney(quote.getDiscountAmount()))
                .payAmount(quote.getPayAmount())
                .payAmountText(formatMoney(quote.getPayAmount()))
                .couponRecordId(quote.getCouponRecordId())
                .couponUsable(quote.isCouponUsable())
                .couponTitle(quote.getCouponTitle())
                .couponReason(quote.getCouponReason())
                .coupons(coupons)
                // 份数 = 凭证张数（D4：多份不合并），付款后就是这个数
                .entitlementCount(quote.getQuantity())
                .entitlementText(entitlementText(course, quote.getTimesGranted(), quote.getQuantity()))
                .ruleSummary(course.getRuleSummary())
                .ruleTexts(course.getRuleTexts())
                .build();
    }

    /** 门槛文案：0 或 null 都是"无门槛"，否则"满 X 可用"。用户看得懂比字段准确更重要 */
    private String thresholdText(BigDecimal threshold) {
        if (threshold == null || threshold.compareTo(BigDecimal.ZERO) <= 0) {
            return "无门槛";
        }
        return "满 " + plain(threshold) + " 可用";
    }

    /** 凭证说明文案：把"买完会得到几张、每张几次"一句话说清，省得用户付完才发现理解错了 */
    private String entitlementText(CourseDTO course, Integer timesGranted, int quantity) {
        if ("UNLIMITED".equals(course.getSaleType())) {
            return "共 " + quantity + " 张凭证，每张在有效期内不限次上课";
        }
        return "共 " + quantity + " 张凭证，每张可上 " + timesGranted + " 次";
    }

    /** 金额去掉多余的 0（12.50 → 12.5），只用于拼文案；显示用的规范格式还是走 formatMoney */
    private String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
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
     * <p>★CAS 那一手的价值不在并发，而在<b>重复提交</b>：
     * {@code UPDATE ... WHERE status='UNPAID'} 影响 0 行就说明这张单已经不是待支付
     * （用户刷了两次、或者已经被超时任务取消了），此时绝不能出票。
     * 出两次票等于白送课，而这条判断只需要一个 WHERE 条件，不用锁、不用幂等表。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pay(Long userId, String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少订单号");
        }
        LocalDateTime now = LocalDateTime.now();

        // ===== ① CAS：UNPAID → PAID。影响 0 行 = 状态已变（重复支付 / 已被超时取消）=====
        if (bookingMapper.markPaid(orderNo, userId, now) == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_ERROR);
        }
        Booking booking = loadOwned(userId, orderNo);

        // ===== ② 出票：份数 = quantity，多份不合并（D4）=====
        int granted = entitlementService.grantForPaidOrder(booking).size();
        log.info("支付成功: userId={}, orderNo={}, 出票 {} 张", userId, orderNo, granted);

        // ===== ③ 站内通知 =====
        notifyPublisher.publishBookingNotify(userId, "支付成功，凭证已生效",
                "「" + booking.getCourseName() + "」已支付，共生成 " + granted + " 张凭证。"
                        + "凭证只能在购买门店约教练，在「我的凭证」里选一张就能去约时间。");
    }

    @Override
    public void cancel(Long userId, String orderNo) {
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
        // 复用用户侧的取消逻辑：状态条件、退券、通知三件事不重复实现一遍
        cancel(booking.getUserId(), orderNo);
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

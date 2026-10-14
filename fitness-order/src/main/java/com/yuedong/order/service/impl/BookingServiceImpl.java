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
import com.yuedong.course.api.dto.CouponUsableDTO;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.order.api.dto.BookingDTO;
import com.yuedong.order.constant.BookingStatus;
import com.yuedong.order.constant.OrderConstants;
import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.model.dto.BookingCreateDTO;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.model.entity.Entitlement;
import com.yuedong.order.model.vo.BookingVO;
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
import java.util.List;
import java.util.Map;
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
    private final CourseClient courseClient;
    private final CouponClient couponClient;
    private final RabbitTemplate rabbitTemplate;
    private final NotifyPublisher notifyPublisher;
    private final ObjectMapper objectMapper;
    private final EntitlementService entitlementService;

    // =====================================================================
    // 创建订单
    // =====================================================================
    @Override
    public BookingVO create(Long userId, BookingCreateDTO dto) {
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
     * 两者都是要写对账才能发现的问题，而一个本地事务就能彻底避免。
     * 这里不需要分布式事务：订单与凭证<b>同在 yd_order 一个库</b>。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pay(Long userId, String orderNo) {
        // ===== 第 1 步：CAS 流转 UNPAID → PAID =====
        // 影响 0 行 = 状态不是 UNPAID（重复支付、或已被超时任务取消）。
        // 同一条语句同时保证"已取消的单不可能被支付"，这也正是超时取消敢用状态判断的原因
        if (bookingMapper.markPaid(orderNo, userId, LocalDateTime.now()) == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_ERROR);
        }

        Booking booking = loadOwned(userId, orderNo);

        // ===== 第 2 步：出票（份数 = quantity，多份不合并，见 D4）=====
        // 有效期由课程服务那边该门店的 valid_type 算：FOREVER→null / DAYS→付款起 N 天 / RANGE→固定截止
        List<Entitlement> granted = entitlementService.grantForPaidOrder(booking);
        log.info("支付成功: orderNo={}, userId={}, payAmount={}, 出票 {} 张",
                orderNo, userId, booking.getPayAmount(), granted.size());

        // ===== 第 3 步：站内通知（文案要把"能约几次"说清楚，用户下一步就是去预约）=====
        Entitlement first = granted.get(0);
        String timesText = first.getTotalTimes() == null
                ? "有效期内不限次预约"
                : "共 " + granted.size() + " 张凭证、每张 " + first.getTotalTimes() + " 次";
        notifyPublisher.publishBookingNotify(userId, "支付成功，凭证已生效",
                "「" + booking.getCourseName() + "」已支付 ¥" + booking.getPayAmount() + "，"
                        + timesText + "。只能在「" + booking.getStoreName() + "」约教练，"
                        + (first.getValidUntil() == null ? "凭证长期有效。" : "请在到期前用完。"));
    }

    @Override
    public void cancel(Long userId, String orderNo) {
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
    public List<BookingVO> list(Long userId, String status) {
        // 查我的订单：WHERE user_id [+ status] ORDER BY id DESC
        // 命中 idx_user_status(user_id, status, id)：等值列在前、排序列在后，索引直接给出有序结果
        List<Booking> bookings = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getUserId, userId)
                .eq(status != null && !status.isBlank() && !"ALL".equals(status), Booking::getStatus, status)
                .orderByDesc(Booking::getId));
        if (bookings.isEmpty()) {
            return new ArrayList<>();
        }

        // ===== 一次 IN 批量取这批订单的凭证 =====
        // ★绝不在循环里逐单查（10 行列表变 10 次查询）。凭证就在本库，
        // 跨表但不跨服务，所以这里连 Feign 都没有
        List<String> orderNos = new ArrayList<>(bookings.size());
        for (Booking booking : bookings) {
            orderNos.add(booking.getOrderNo());
        }
        Map<String, List<Entitlement>> entitlementMap =
                entitlementService.mapByOrderNos(userId, orderNos);

        List<BookingVO> result = new ArrayList<>(bookings.size());
        for (Booking booking : bookings) {
            result.add(toVO(booking,
                    entitlementMap.getOrDefault(booking.getOrderNo(), new ArrayList<>())));
        }
        return result;
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

package com.yuedong.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.constant.MqConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.api.client.CouponClient;
import com.yuedong.course.api.client.CourseClient;
import com.yuedong.course.api.dto.CouponUsableDTO;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.order.api.dto.BookingDTO;
import com.yuedong.order.constant.BookingStatus;
import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.model.dto.BookingCreateDTO;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.model.vo.BookingVO;
import com.yuedong.order.mq.NotifyPublisher;
import com.yuedong.order.mq.message.OrderTimeoutMessage;
import com.yuedong.order.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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

    // =====================================================================
    // 创建订单
    // =====================================================================
    @Override
    public BookingVO create(Long userId, BookingCreateDTO dto) {
        // ===== 第 1 步：查课程快照（Feign 只读，不写远端任何东西）=====
        // 一次调用同时拿到课程字段、门店名与该店的可售状态，避免"查课程 + 再查门店"两次 RPC
        var detailResult = courseClient.detail(dto.getCourseId(), dto.getStoreId());
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
        int quantity = 1;                       // 份数，购物车分支再接入
        BigDecimal originalAmount = course.getPrice().multiply(BigDecimal.valueOf(quantity));
        BigDecimal discountAmount = BigDecimal.ZERO;
        String couponTitle = null;
        if (dto.getCouponRecordId() != null) {
            var couponResult = couponClient.info(dto.getCouponRecordId(), userId, originalAmount);
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
        // TIMES：一单先给 1 张凭证（份数由 quantity 决定）；UNLIMITED 存 null 表示不限次
        booking.setTimesGranted("UNLIMITED".equals(course.getSaleType()) ? null : 1);
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
            var useResult = couponClient.use(dto.getCouponRecordId(), userId, booking.getOrderNo());
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

        log.info("购买订单已创建: orderNo={}, userId={}, courseId={}, storeId={}, payAmount={}",
                booking.getOrderNo(), userId, course.getId(), course.getStoreId(), payAmount);
        return toVO(booking);
    }

    // =====================================================================
    // 支付与取消
    // =====================================================================
    @Override
    public void pay(Long userId, String orderNo) {
        // ===== 第 1 步：CAS 流转 UNPAID → PAID =====
        // 影响 0 行 = 状态不是 UNPAID（重复支付、或已被超时任务取消）。
        // 同一条语句同时保证"已取消的单不可能被支付"，这也正是超时取消敢用状态判断的原因
        if (bookingMapper.markPaid(orderNo, userId, LocalDateTime.now()) == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_ERROR);
        }

        Booking booking = loadOwned(userId, orderNo);
        log.info("支付成功: orderNo={}, userId={}, payAmount={}", orderNo, userId, booking.getPayAmount());

        // ★凭证分支在这里接：为该单生成 quantity 张 t_entitlement，
        //   valid_until 由 t_course_store 的可使用窗口算出。
        //   目前"买完还没有可约的凭证"，属于分批交付的中间状态，不是漏写。

        notifyPublisher.publishBookingNotify(userId, "支付成功",
                "「" + booking.getCourseName() + "」已支付 ¥" + booking.getPayAmount() + "，可以开始预约教练了。");
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
            var refundResult = couponClient.refund(booking.getCouponRecordId());
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

        List<BookingVO> result = new ArrayList<>();
        for (Booking booking : bookings) {
            result.add(toVO(booking));
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

    /** 实体 → C 端 VO */
    private BookingVO toVO(Booking booking) {
        List<String> actions = new ArrayList<>();
        if (BookingStatus.UNPAID.equals(booking.getStatus())) {
            actions.add("PAY");
            actions.add("CANCEL");
        }
        // ★BOOK（去约教练）与 RATE（去评价）要读凭证与评价数据（t_entitlement / t_rating），
        //   在对应分支接入后再加；现在给出去只会做出点不动的按钮，比不给更糟

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
                .couponTitle(booking.getCouponTitle())
                .status(booking.getStatus())
                .statusText(BookingStatus.textOf(booking.getStatus()))
                .fromSeckill(booking.getActivityId() != null)
                .createdAtText(formatTime(booking.getCreatedAt()))
                .payTimeText(formatTime(booking.getPayTime()))
                .cancelReason(booking.getCancelReason())
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

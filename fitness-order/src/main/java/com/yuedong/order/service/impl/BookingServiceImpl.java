package com.yuedong.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.constant.MqConstants;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.api.client.CourseClient;
import com.yuedong.course.api.client.CouponClient;
import com.yuedong.course.api.dto.CouponUsableDTO;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.ScheduleDTO;
import com.yuedong.order.api.dto.BookingDTO;
import com.yuedong.order.constant.BookingStatus;
import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.model.dto.BookingCreateDTO;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.model.vo.BookingVO;
import com.yuedong.order.mq.NotifyPublisher;
import com.yuedong.order.mq.RabbitConfig;
import com.yuedong.order.mq.message.OrderTimeoutMessage;
import com.yuedong.order.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 预约订单实现：普通预约全流程（场次占用 + 状态机 + 核销码 + 延迟取消 + 站内通知）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    /** 未支付订单保留时长（分钟），超时由延迟消息自动取消 */
    private static final long UNPAID_TIMEOUT_MINUTES = 15;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BookingMapper bookingMapper;
    private final CourseClient courseClient;
    private final CouponClient couponClient;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final NotifyPublisher notifyPublisher;

    // =====================================================================
    // 普通预约（按场次）
    // =====================================================================
    @Override
    public BookingVO create(Long userId, BookingCreateDTO dto) {
        // ===== 第 1 步：查课程简况与场次（Feign 调课程服务） =====
        var courseResult = courseClient.detail(dto.getCourseId());
        if (courseResult.getCode() != 0 || courseResult.getData() == null) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }
        var course = courseResult.getData();

        var scheduleResult = courseClient.schedule(dto.getScheduleId());
        if (scheduleResult.getCode() != 0 || scheduleResult.getData() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "场次不存在或已取消");
        }
        ScheduleDTO schedule = scheduleResult.getData();
        // 场次必须属于这门课（防错传别课程的场次 id）
        if (!Objects.equals(schedule.getCourseId(), dto.getCourseId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "场次与课程不匹配");
        }
        String bookTime = schedule.getScheduleTime().format(TIME_FMT);

        // ===== 第 1.5 步：优惠券校验与价格计算（用券时） =====
        BigDecimal finalPrice = course.getPrice();
        BigDecimal discountAmount = BigDecimal.ZERO;
        String couponTitle = null;
        if (dto.getCouponRecordId() != null) {
            var couponResult = couponClient.info(dto.getCouponRecordId(), userId, course.getPrice());
            if (couponResult.getCode() != 0 || couponResult.getData() == null
                    || !couponResult.getData().isUsable()) {
                String reason = couponResult.getData() == null ? "优惠券不可用" : couponResult.getData().getReason();
                throw new BusinessException(ErrorCode.COUPON_NOT_USABLE, reason);
            }
            CouponUsableDTO coupon = couponResult.getData();
            couponTitle = coupon.getTitle();
            discountAmount = coupon.getDiscount();
            finalPrice = course.getPrice().subtract(discountAmount).max(BigDecimal.ZERO);
        }

        // ===== 第 2 步：同人同课去重（存在未完成的有效单就不允许再约） =====
        Long exists = bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getUserId, userId)
                .eq(Booking::getCourseId, dto.getCourseId())
                .in(Booking::getStatus, BookingStatus.UNPAID, BookingStatus.BOOKED));
        if (exists > 0) {
            throw new BusinessException(ErrorCode.BOOKING_DUPLICATE);
        }

        // ===== 第 3 步：满员预检（真正防超约在第 5 步的原子 UPDATE） =====
        if (schedule.getBooked() >= schedule.getCapacity()) {
            throw new BusinessException(ErrorCode.COURSE_FULL);
        }

        // ===== 第 4 步：Feign 原子占用场次名额 =====
        var occupyResult = courseClient.occupySchedule(dto.getScheduleId());
        if (occupyResult.getCode() != 0 || !Boolean.TRUE.equals(occupyResult.getData())) {
            throw new BusinessException(ErrorCode.COURSE_FULL);
        }

        // ===== 第 5 步：创建待支付订单；落库失败要回补名额（补偿逻辑） =====
        Booking booking = new Booking();
        booking.setOrderNo(genOrderNo());
        booking.setUserId(userId);
        booking.setCourseId(course.getId());
        booking.setScheduleId(schedule.getId());
        booking.setCourseName(course.getName());
        booking.setCoach(course.getCoach());
        booking.setBookTime(bookTime);
        booking.setStoreName(course.getStoreName());
        booking.setPrice(finalPrice);
        booking.setDiscountAmount(discountAmount);
        booking.setCouponRecordId(dto.getCouponRecordId());
        booking.setStatus(BookingStatus.UNPAID);
        try {
            bookingMapper.insert(booking);
        } catch (Exception e) {
            courseClient.releaseSchedule(dto.getScheduleId()); // 补偿：释放刚占的名额
            log.error("预约订单落库失败，已释放名额: userId={}, scheduleId={}", userId, dto.getScheduleId(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "预约失败，请重试");
        }

        // ===== 第 5.5 步：核销优惠券（CAS：UNUSED → USED 绑定订单号；失败回滚名额） =====
        if (dto.getCouponRecordId() != null) {
            var useResult = couponClient.use(dto.getCouponRecordId(), userId, booking.getOrderNo());
            if (useResult.getCode() != 0 || !Boolean.TRUE.equals(useResult.getData())) {
                courseClient.releaseSchedule(dto.getScheduleId());
                log.error("用券核销失败，已释放名额: userId={}, recordId={}", userId, dto.getCouponRecordId());
                throw new BusinessException(ErrorCode.COUPON_NOT_USABLE, "优惠券核销失败，请重试");
            }
        }

        // ===== 第 6 步：发 15 分钟延迟取消消息 + 站内通知 =====
        sendTimeoutMessage(booking.getOrderNo(), userId);
        notifyPublisher.publishBookingNotify(userId, "预约创建成功",
                "已为你锁定「" + course.getName() + "」" + bookTime + " 的名额"
                        + (couponTitle != null ? "（已用" + couponTitle + "，优惠 ¥" + discountAmount + "）" : "")
                        + "，请在 " + UNPAID_TIMEOUT_MINUTES + " 分钟内完成支付，超时将自动释放。");

        log.info("预约成功: orderNo={}, userId={}, scheduleId={}", booking.getOrderNo(), userId, schedule.getId());
        return toVO(booking);
    }

    @Override
    public List<BookingVO> list(Long userId, String status) {
        List<Booking> bookings = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getUserId, userId)
                .eq(status != null && !status.isBlank() && !"ALL".equals(status), Booking::getStatus, status)
                .orderByDesc(Booking::getId));
        return bookings.stream().map(this::toVO).toList();
    }

    // =====================================================================
    // 支付（模拟）：支付成功即预约生效，生成核销码 + 站内通知
    // =====================================================================
    @Override
    public void pay(Long userId, String orderNo) {
        // ===== 第 1 步：CAS 状态流转 UNPAID → BOOKED（重复支付影响行数为 0） =====
        int rows = bookingMapper.markPaid(orderNo, userId);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_ERROR);
        }

        // ===== 第 2 步：生成核销码（10 位数字，唯一索引兜底重试） =====
        Booking booking = bookingMapper.selectOne(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getOrderNo, orderNo));
        for (int i = 0; i < 3; i++) {
            String code = genVerifyCode();
            try {
                Booking patch = new Booking();
                patch.setId(booking.getId());
                patch.setVerifyCode(code);
                bookingMapper.updateById(patch);
                booking.setVerifyCode(code);
                break;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                if (i == 2) log.error("核销码生成连续冲突: orderNo={}", orderNo);
            }
        }

        // ===== 第 3 步：站内通知 =====
        notifyPublisher.publishBookingNotify(userId, "预约生效",
                "「" + booking.getCourseName() + "」" + booking.getBookTime()
                        + " 预约成功，到店出示核销码即可入场。");

        log.info("模拟支付成功: orderNo={}, 核销码已生成", orderNo);
    }

    @Override
    public void cancel(Long userId, String orderNo) {
        // ===== 第 1 步：CAS 取消（UNPAID/BOOKED → CANCELED），并发下只成功一次 =====
        Booking booking = bookingMapper.selectOne(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getOrderNo, orderNo).eq(Booking::getUserId, userId));
        if (booking == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }
        int rows = bookingMapper.cancel(orderNo, "用户主动取消");
        if (rows == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_ERROR);
        }

        // ===== 第 2 步：释放名额（按预约方式回补到场次或课程） + 退券 =====
        if (BookingStatus.occupiesSlot(booking.getStatus())) {
            releaseSlot(booking);
            if (booking.getCouponRecordId() != null) {
                couponClient.refund(booking.getCouponRecordId()); // 券退回为未使用
            }
            notifyPublisher.publishBookingNotify(userId, "预约已取消",
                    "「" + booking.getCourseName() + "」" + booking.getBookTime() + " 的预约已取消，名额已释放"
                            + (booking.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0 ? "，优惠券已退回。" : "。"));
        }
        log.info("订单已取消: orderNo={}, 原状态={}", orderNo, booking.getStatus());
    }

    // =====================================================================
    // 核销（管理端工作台调用）：核销码 + BOOKED → FINISHED
    // =====================================================================
    @Override
    public BookingDTO verifyByCode(String verifyCode) {
        // ===== 第 1 步：按码查订单（用于展示与状态预判） =====
        Booking booking = bookingMapper.selectOne(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getVerifyCode, verifyCode));
        if (booking == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "核销码无效");
        }

        // ===== 第 2 步：CAS 核销（重复核销影响行数为 0） =====
        int rows = bookingMapper.markVerified(verifyCode);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_ERROR,
                    "该核销码已使用或订单状态不允许核销");
        }

        // ===== 第 3 步：站内通知 =====
        notifyPublisher.publishBookingNotify(booking.getUserId(), "核销成功",
                "「" + booking.getCourseName() + "」" + booking.getBookTime() + " 已完成核销，期待你的下次训练。");

        log.info("核销成功: orderNo={}, code={}", booking.getOrderNo(), verifyCode);
        Booking latest = bookingMapper.selectById(booking.getId());
        return toDTO(latest);
    }

    // =====================================================================
    // 管理端（fitness-admin-server Feign 调用）
    // =====================================================================
    @Override
    public PageDTO<BookingDTO> adminPage(
            String status, String keyword, Integer page, Integer size) {
        var result = bookingMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        page == null ? 1 : page, size == null ? 10 : size),
                new LambdaQueryWrapper<Booking>()
                        .eq(status != null && !status.isBlank() && !"ALL".equals(status),
                                Booking::getStatus, status)
                        .like(keyword != null && !keyword.isBlank(), Booking::getOrderNo, keyword)
                        .orderByDesc(Booking::getId));
        return PageDTO.of(
                result.getRecords().stream().map(this::toDTO).toList(), result.getTotal());
    }

    @Override
    public void adminCancel(String orderNo) {
        // ===== 管理端代客取消：与用户取消同一套 CAS + 释放 + 退券 + 通知 =====
        Booking booking = bookingMapper.selectOne(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getOrderNo, orderNo));
        if (booking == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }
        int rows = bookingMapper.cancel(orderNo, "管理端取消");
        if (rows == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_ERROR);
        }
        if (BookingStatus.occupiesSlot(booking.getStatus())) {
            releaseSlot(booking);
            if (booking.getCouponRecordId() != null) {
                couponClient.refund(booking.getCouponRecordId());
            }
            notifyPublisher.publishBookingNotify(booking.getUserId(), "预约已取消",
                    "「" + booking.getCourseName() + "」" + booking.getBookTime() + " 的预约已被平台取消，名额已释放，如有疑问请联系客服。");
        }
        log.info("管理端取消订单: orderNo={}", orderNo);
    }

    @Override
    public List<BookingDTO> verifiedRecent() {
        return bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                        .isNotNull(Booking::getVerifiedAt)
                        .orderByDesc(Booking::getVerifiedAt)
                        .last("LIMIT 10"))
                .stream().map(this::toDTO).toList();
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    /** 按预约方式释放名额：有场次释放场次，否则回补课程 */
    public void releaseSlot(Booking booking) {
        if (booking.getScheduleId() != null) {
            courseClient.releaseSchedule(booking.getScheduleId());
        } else {
            courseClient.release(booking.getCourseId());
        }
    }

    /** 发送延迟取消消息（15 分钟后消费） */
    private void sendTimeoutMessage(String orderNo, Long userId) {
        try {
            OrderTimeoutMessage message = new OrderTimeoutMessage(orderNo, userId);
            rabbitTemplate.convertAndSend(
                    MqConstants.ORDER_DELAY_EXCHANGE,
                    MqConstants.ORDER_TIMEOUT_KEY,
                    objectMapper.writeValueAsString(message),
                    msg -> {
                        // x-delay 头：延迟插件按毫秒精准投递
                        msg.getMessageProperties().setDelay((int) (UNPAID_TIMEOUT_MINUTES * 60 * 1000));
                        return msg;
                    });
        } catch (Exception e) {
            log.error("延迟取消消息发送失败: orderNo={}", orderNo, e);
        }
    }

    /** 10 位数字核销码（SecureRandom 防猜测） */
    private String genVerifyCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    /** 订单号：B + 时间戳 + 3 位随机 */
    private String genOrderNo() {
        return "B" + System.currentTimeMillis()
                + ThreadLocalRandom.current().nextInt(100, 1000);
    }

    private BookingVO toVO(Booking b) {
        return BookingVO.builder()
                .id(b.getOrderNo())
                .courseId(b.getCourseId())
                .courseName(b.getCourseName())
                .coach(b.getCoach())
                .time(b.getBookTime())
                .storeName(b.getStoreName())
                .price(b.getPrice())
                .discountAmount(b.getDiscountAmount())
                .status(b.getStatus())
                .createdAt(b.getCreatedAt())
                .fromSeckill(b.getActivityId() != null)
                .verifyCode(b.getVerifyCode())
                .build();
    }

    /** 实体 → 内部接口 DTO（比 VO 多 userId，供管理端聚合会员昵称） */
    private BookingDTO toDTO(Booking b) {
        return BookingDTO.builder()
                .id(b.getOrderNo())
                .userId(b.getUserId())
                .courseId(b.getCourseId())
                .courseName(b.getCourseName())
                .coach(b.getCoach())
                .time(b.getBookTime())
                .storeName(b.getStoreName())
                .price(b.getPrice())
                .discountAmount(b.getDiscountAmount())
                .status(b.getStatus())
                .createdAt(b.getCreatedAt())
                .fromSeckill(b.getActivityId() != null)
                .verifyCode(b.getVerifyCode())
                .build();
    }
}

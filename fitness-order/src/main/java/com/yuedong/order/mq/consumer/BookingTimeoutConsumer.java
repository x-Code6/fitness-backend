package com.yuedong.order.mq.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.constant.MqConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.course.api.client.CourseClient;
import com.yuedong.course.api.client.CouponClient;
import com.yuedong.order.constant.BookingStatus;
import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.mq.NotifyPublisher;
import com.yuedong.order.mq.RabbitConfig;
import com.yuedong.order.mq.message.OrderTimeoutMessage;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 订单超时取消消费者：下单 15 分钟后仍未支付 → 自动取消并释放名额。
 *
 * <p>状态安全：cancel 是 CAS 式 UPDATE（仅 UNPAID/BOOKED 可取消），
 * 用户恰好在这时支付成功的话，这里影响行数为 0，不会误取消。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingTimeoutConsumer {

    private final BookingMapper bookingMapper;
    private final CourseClient courseClient;
    private final CouponClient couponClient;
    private final ObjectMapper objectMapper;
    private final NotifyPublisher notifyPublisher;

    @RabbitListener(queues = MqConstants.ORDER_TIMEOUT_QUEUE)
    public void onMessage(String json, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            OrderTimeoutMessage msg = objectMapper.readValue(json, OrderTimeoutMessage.class);

            // ===== 第 1 步：查订单，只有仍未支付的才需要取消 =====
            Booking booking = bookingMapper.selectOne(new LambdaQueryWrapper<Booking>()
                    .eq(Booking::getOrderNo, msg.getOrderNo()));
            if (booking == null || !BookingStatus.UNPAID.equals(booking.getStatus())) {
                // 已支付/已取消/不存在：无事可做
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ===== 第 2 步：CAS 取消（并发支付时这里影响行数为 0） =====
            int rows = bookingMapper.cancel(msg.getOrderNo(), "超时未支付，系统自动取消");
            if (rows > 0 && BookingStatus.occupiesSlot(booking.getStatus())) {
                // ===== 第 3 步：释放名额（场次或课程） + 退券 + 站内通知 =====
                if (booking.getScheduleId() != null) {
                    courseClient.releaseSchedule(booking.getScheduleId());
                } else {
                    courseClient.release(booking.getCourseId());
                }
                if (booking.getCouponRecordId() != null) {
                    couponClient.refund(booking.getCouponRecordId()); // 券退回为未使用
                }
                notifyPublisher.publishBookingNotify(msg.getUserId(), "订单已自动取消",
                        "「" + booking.getCourseName() + "」的预约超时未支付，名额已自动释放"
                                + (booking.getDiscountAmount() != null && booking.getDiscountAmount().compareTo(java.math.BigDecimal.ZERO) > 0
                                ? "，优惠券已退回。" : "。"));
                log.info("订单超时自动取消: orderNo={}", msg.getOrderNo());
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("订单超时取消处理失败: {}", json, e);
            // 重试一次（requeue=true），仍失败则进死信（该队列未配 DLX 则消息丢弃，生产应配）
            channel.basicNack(deliveryTag, false, true);
        }
    }
}

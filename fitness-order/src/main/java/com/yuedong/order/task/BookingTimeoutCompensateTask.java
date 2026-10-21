package com.yuedong.order.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.constant.MqConstants;
import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.mq.message.OrderTimeoutMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时未支付订单的兜底任务。
 *
 * <p>★解决什么：下单时那条 15 分钟延迟消息如果没发出去（MQ 抖动/交换机未就绪），
 * 订单会永远停在 UNPAID。这个任务每分钟扫一次“超过 15 分钟还没付”的订单，
 * 重新投一条延迟为 0 的超时消息，让原来的消费者完成取消、退券、回补名额。</p>
 *
 * <p>★为什么不在任务里直接改状态：取消要同时退券、回补秒杀 Redis/DB 名额，
 * 那套逻辑已经在 {@code BookingTimeoutConsumer} 里。这里只负责重投，避免出现第二份取消实现。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingTimeoutCompensateTask {

    /** 与下单时延迟消息保持一致的超时分钟数 */
    private static final int UNPAID_TIMEOUT_MINUTES = 15;

    /** 一轮最多补投多少条，避免一次扫太多把 MQ 打满 */
    private static final int BATCH_SIZE = 50;

    private final BookingMapper bookingMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 60_000)
    public void compensate() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(UNPAID_TIMEOUT_MINUTES);
        List<Booking> orders = bookingMapper.selectUnpaidBefore(cutoff, BATCH_SIZE);
        if (orders.isEmpty()) {
            return;
        }

        for (Booking booking : orders) {
            try {
                OrderTimeoutMessage message = new OrderTimeoutMessage(
                        booking.getOrderNo(), booking.getUserId());
                String json = objectMapper.writeValueAsString(message);
                rabbitTemplate.convertAndSend(
                        MqConstants.ORDER_DELAY_EXCHANGE,
                        MqConstants.ORDER_TIMEOUT_KEY,
                        json,
                        msg -> {
                            // 已经超时了，不需要再等 15 分钟；0 表示尽快投递
                            msg.getMessageProperties().setDelay(0);
                            msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                            return msg;
                        });
                log.warn("补投超时取消消息: orderNo={}, userId={}",
                        booking.getOrderNo(), booking.getUserId());
            } catch (Exception e) {
                // 下一分钟还会重试；订单仍是 UNPAID，不会丢
                log.error("补投超时取消消息失败: orderNo={}", booking.getOrderNo(), e);
            }
        }
    }
}

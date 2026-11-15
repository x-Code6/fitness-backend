package com.yuedong.user.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.constant.MqConstants;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.user.mapper.MessageMapper;
import com.yuedong.user.model.entity.Message;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 站内通知事件消费者：所有业务域的通知事件（notify.#）→ 落 t_message。
 *
 * <p>事件由订单/积分/社区服务发布到 ex.notify.exchange（topic 交换机），
 * 本队列以 notify.# 绑定接收全部；新业务域接入只需按 notify.xxx 发消息，
 * 本消费者零改动——事件驱动的扩展点。</p>
 *
 * <p>注意：本文件里出现两种 Message（业务实体与 AMQP 信封），
 * AMQP 的用全限定名 org.springframework.amqp.core.Message 区分。</p>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class NotifyEventConsumer {

    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    /** 通知事件去重：发布方 eventId 是 UUID，重投时保持不变 */
    private final StringRedisTemplate redis;

    /**
     * ★{@code ackMode = "MANUAL"} 不是可选项：方法体里自己调了 basicAck / basicNack，
     * 而容器的默认确认模式是 AUTO——Spring 会在方法返回后再 ack 一次，
     * 第二次 ack 的就是一个已经不存在的 delivery tag，broker 直接
     * {@code PRECONDITION_FAILED - unknown delivery tag 1} 关掉信道，
     * 表现是消费者每隔一会儿就"Restarting Consumer"，日志里全是信道错误。
     * 手动确认就得明确声明手动，两边只能有一方负责 ack。
     */
    @RabbitListener(queues = MqConstants.USER_NOTIFY_QUEUE, ackMode = "MANUAL")
    public void onMessage(String json, org.springframework.amqp.core.Message amqpMessage, Channel channel) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        String idempotentKey = null;
        try {
            var event = objectMapper.readValue(json, java.util.Map.class);

            // 发布方 eventId 是 UUID，Rabbit 重投时保持不变。用它做 24h 去重，
            // 避免重复通知在站内信里出现两条一模一样的。
            Object eventIdValue = event.get("eventId");
            Object userIdValue = event.get("userId");
            if (eventIdValue == null || userIdValue == null) {
                throw new IllegalArgumentException("通知事件缺少 eventId 或 userId");
            }
            String eventId = String.valueOf(eventIdValue);
            idempotentKey = RedisKeyConstants.idempotentMsg(eventId);
            Boolean first = redis.opsForValue().setIfAbsent(
                    idempotentKey, "1", Duration.ofHours(24));
            if (!Boolean.TRUE.equals(first)) {
                log.info("站内通知重复，跳过: eventId={}", eventId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ===== 组装并落库 =====
            Message msg = new Message();
            msg.setUserId(((Number) userIdValue).longValue());
            msg.setTitle(String.valueOf(event.getOrDefault("title", "通知")));
            msg.setContent(String.valueOf(event.getOrDefault("content", "")));
            msg.setMsgType(String.valueOf(event.getOrDefault("msgType", "BOOKING")));
            msg.setIsRead(0);
            msg.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(msg);

            channel.basicAck(deliveryTag, false);
            log.debug("站内消息已生成: userId={}, title={}", msg.getUserId(), msg.getTitle());
        } catch (Exception e) {
            // 落库失败时删掉去重 key，否则人工重投会被误判成重复而直接 ack
            if (idempotentKey != null) {
                try {
                    redis.delete(idempotentKey);
                } catch (Exception deleteError) {
                    log.error("站内通知失败后清理幂等 key 失败: key={}", idempotentKey, deleteError);
                }
            }
            log.error("站内通知处理失败: {}", json, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}

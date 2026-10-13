package com.yuedong.user.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.user.mapper.MessageMapper;
import com.yuedong.user.model.entity.Message;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
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

    @RabbitListener(queues = RabbitConfig.USER_NOTIFY_QUEUE)
    public void onMessage(String json, org.springframework.amqp.core.Message amqpMessage, Channel channel) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        try {
            var event = objectMapper.readValue(json, java.util.Map.class);

            // ===== 组装并落库（发布方 eventId 保证唯一；重复消费影响仅为多一条同文消息，演示可接受） =====
            Message msg = new Message();
            msg.setUserId(((Number) event.get("userId")).longValue());
            msg.setTitle(String.valueOf(event.getOrDefault("title", "通知")));
            msg.setContent(String.valueOf(event.getOrDefault("content", "")));
            msg.setMsgType(String.valueOf(event.getOrDefault("msgType", "BOOKING")));
            msg.setIsRead(0);
            msg.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(msg);

            channel.basicAck(deliveryTag, false);
            log.debug("站内消息已生成: userId={}, title={}", msg.getUserId(), msg.getTitle());
        } catch (Exception e) {
            log.error("站内通知处理失败: {}", json, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}

package com.yuedong.point.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.yuedong.common.constant.MqConstants;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 站内通知事件发布器（积分域）：积分变动 → user 服务生成站内消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyPublisher {


    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public void publishPointNotify(Long userId, String title, String content) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", UUID.randomUUID().toString());
            event.put("userId", userId);
            event.put("msgType", "POINT");
            event.put("title", title);
            event.put("content", content);
            rabbitTemplate.convertAndSend(MqConstants.NOTIFY_EXCHANGE, MqConstants.ROUTING_NOTIFY_POINT,
                    objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("积分通知发布失败: userId={}", userId, e);
        }
    }
}

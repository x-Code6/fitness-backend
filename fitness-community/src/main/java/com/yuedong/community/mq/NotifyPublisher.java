package com.yuedong.community.mq;

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
 * 站内通知事件发布器（社区域）：评论/点赞 → 动态作者收到站内消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyPublisher {


    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public void publishCommunityNotify(Long userId, String title, String content) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", UUID.randomUUID().toString());
            event.put("userId", userId);
            event.put("msgType", "INTERACTION");
            event.put("title", title);
            event.put("content", content);
            rabbitTemplate.convertAndSend(MqConstants.NOTIFY_EXCHANGE, MqConstants.ROUTING_NOTIFY_COMMUNITY,
                    objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("社区通知发布失败: userId={}", userId, e);
        }
    }
}

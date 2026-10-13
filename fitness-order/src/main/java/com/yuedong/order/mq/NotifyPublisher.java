package com.yuedong.order.mq;

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
 * 站内通知事件发布器：订单域的事件 → user 服务消费后生成站内消息。
 *
 * <p>事件驱动的好处：订单服务不关心"消息长什么样、存哪里"，
 * 只管把"发生了什么"广播出去，通知的展示形态由 user 服务决定。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyPublisher {


    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /** 发布预约域通知：title/content 展示给用户，msgType 用于前端分类图标 */
    public void publishBookingNotify(Long userId, String title, String content) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", UUID.randomUUID().toString());
            event.put("userId", userId);
            event.put("msgType", "BOOKING");
            event.put("title", title);
            event.put("content", content);
            rabbitTemplate.convertAndSend(MqConstants.NOTIFY_EXCHANGE, MqConstants.ROUTING_NOTIFY_BOOKING,
                    objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("预约通知发布失败: userId={}, title={}", userId, title, e);
        }
    }
}

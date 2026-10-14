package com.yuedong.community.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.constant.MqConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 浏览事件发布器：<b>复用 behavior 那条 Kafka 通道</b>（docs 4.12 K1）。
 *
 * <p>★为什么不新开一个 view topic：计数本身一次 {@code HINCRBY} 就够，不需要异步；
 * Kafka 在这里的价值是"留下原始明细、可重放"，而 {@code fitness-behavior}
 * 已经在做这件事（消费者会把事件写进 {@code t_behavior_log}）。
 * 多一条链路就是多一套 consumer、多一套失败分支——已有能力能复用就不要新建。</p>
 *
 * <p>★{@code eventId} 故意做成 {@code postview:帖子:人:日期} 而不是 UUID：
 * 这样它同时是"事件 id"和"一人一帖一天"的<b>幂等键</b>，
 * 落到 {@code t_behavior_log} 时撞 {@code uk_event} 唯一索引就会被消费者丢掉。
 * Redis 那层去重（SADD）挡掉绝大多数重复，这条唯一索引兜住并发穿过来的那一小部分。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostViewPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * @param eventId 幂等键（见类注释），由 PostViewServiceImpl 用"帖:人:日"拼出
     * @param postId  被浏览的动态
     * @param userId  浏览者（本方法只在已登录分支被调用，不会为 null）
     */
    public void publish(String eventId, Long postId, Long userId) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", eventId);
            event.put("type", "post_view");
            event.put("targetId", postId);
            // deviceId 这一列 NOT NULL：服务端产生的事件没有前端设备号，用"来源:用户"占位，
            // 归因口径保持稳定（同一用户的社区浏览事件永远落在同一个 key 上，分区有序）
            event.put("deviceId", "community:" + userId);
            event.put("userId", userId);
            event.put("ts", System.currentTimeMillis());
            kafkaTemplate.send(MqConstants.BEHAVIOR_TOPIC, "community:" + userId,
                    objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            // ★发送失败只记日志，绝不向上传播：浏览量是统计口径，不是业务状态，
            // 它不该让"看动态"这件事失败
            log.warn("浏览事件发送失败: eventId={}, err={}", eventId, e.getMessage());
        }
    }
}

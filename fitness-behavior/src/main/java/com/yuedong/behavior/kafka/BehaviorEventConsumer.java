package com.yuedong.behavior.kafka;

import com.yuedong.common.constant.MqConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.behavior.mapper.BehaviorLogMapper;
import com.yuedong.behavior.model.entity.BehaviorLog;
import com.yuedong.behavior.service.impl.BehaviorServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 埋点事件消费者：Kafka → 落库（明细层） + 热度聚合（course_click → ZSet）。
 *
 * <p>key=deviceId 保证同一设备的事件落在同一分区、按序消费；
 * eventId 在 Redis SETNX 做幂等（Kafka 至少一次投递语义，可能重复）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorEventConsumer {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final String HOT_KEY_PREFIX = "behavior:hot:course:";

    private final BehaviorLogMapper behaviorLogMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = MqConstants.BEHAVIOR_TOPIC, groupId = "yd-behavior-consumer")
    public void onMessage(String json) {
        try {
            // ===== 第 1 步：解析 + 幂等（Kafka 至少一次投递，可能重复） =====
            Map<String, Object> event = objectMapper.readValue(json, Map.class);
            String eventId = String.valueOf(event.get("eventId"));
            Boolean first = redis.opsForValue().setIfAbsent(
                    "behavior:seen:" + eventId, "1", Duration.ofHours(24));
            if (!Boolean.TRUE.equals(first)) {
                return; // 重复事件，直接跳过
            }

            // ===== 第 2 步：落明细库 =====
            BehaviorLog logRow = new BehaviorLog();
            logRow.setEventId(eventId);
            logRow.setEventType(String.valueOf(event.get("type")));
            logRow.setTargetId(event.get("targetId") == null ? null
                    : Long.valueOf(String.valueOf(event.get("targetId"))));
            logRow.setDeviceId(String.valueOf(event.get("deviceId")));
            logRow.setUserId(event.get("userId") == null ? null
                    : Long.valueOf(String.valueOf(event.get("userId"))));
            logRow.setClientTs(event.get("ts") == null ? null
                    : Long.valueOf(String.valueOf(event.get("ts"))));
            logRow.setCreatedAt(java.time.LocalDateTime.now());
            behaviorLogMapper.insert(logRow);

            // ===== 第 3 步：课程点击 → 热度榜 ZINCRBY（首页推荐排序数据源） =====
            if ("course_click".equals(logRow.getEventType()) && logRow.getTargetId() != null) {
                String hotKey = HOT_KEY_PREFIX + LocalDate.now().format(MONTH_FMT);
                redis.opsForZSet().incrementScore(hotKey,
                        String.valueOf(logRow.getTargetId()), 1);
                redis.expire(hotKey, Duration.ofDays(40)); // 保留 40 天，跨月自然滚动
            }
        } catch (Exception e) {
            // 解析/落库失败只打日志（埋点允许少量丢失），不阻断消费组
            log.error("埋点事件处理失败: {}", json, e);
        }
    }
}

package com.yuedong.behavior.service.impl;

import com.yuedong.common.constant.MqConstants;
import com.yuedong.common.constant.RedisKeyConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.behavior.model.dto.ReportDTO;
import com.yuedong.behavior.model.vo.HotCourseVO;
import com.yuedong.behavior.service.BehaviorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 埋点实现。
 *
 * <p>双 MQ 分工（面试口径）：
 * 业务指令类消息（秒杀建单/延迟取消/积分事件）走 RabbitMQ——要可靠投递、延迟、死信；
 * 行为埋点流（高吞吐 append-only、可回溯、按设备有序）走 Kafka。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorServiceImpl implements BehaviorService {

    /** 埋点 topic：key=deviceId，同一设备的事件进同一分区、按序消费 */
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public void report(Long userId, ReportDTO dto) {
        // ===== 第 1 步：补全身份并逐条发 Kafka =====
        // 匿名用户 userId 为 null（网关白名单放行），仅以 deviceId 标识
        for (ReportDTO.Event event : dto.getEvents()) {
            try {
                Map<String, Object> message = new HashMap<>();
                message.put("eventId", event.getEventId());
                message.put("type", event.getType());
                message.put("targetId", event.getTargetId());
                message.put("deviceId", dto.getDeviceId());
                message.put("userId", userId);
                message.put("ts", event.getTs() == null ? System.currentTimeMillis() : event.getTs());
                // ★props 原样带上：这是"从哪个位置点进来"这类问题的唯一载体。
                //   以前整条链路把它丢了，库里就只剩 eventType + targetId，分析价值掉一半
                message.put("props", event.getProps());
                // key=deviceId → 同设备事件进同一分区，消费端按序处理
                kafkaTemplate.send(MqConstants.BEHAVIOR_TOPIC, dto.getDeviceId(), objectMapper.writeValueAsString(message));
            } catch (Exception e) {
                // 单条发送失败不影响整批（埋点允许少量丢失，主业务零影响）
                log.warn("埋点发送失败: eventId={}, err={}", event.getEventId(), e.getMessage());
            }
        }
    }

    @Override
    public List<HotCourseVO> hotCourses(int limit) {
        String key = RedisKeyConstants.BEHAVIOR_HOT_COURSE + LocalDate.now().format(MONTH_FMT);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(key, 0, Math.max(1, limit) - 1);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        return tuples.stream()
                .map(t -> new HotCourseVO(Long.valueOf(t.getValue()),
                        t.getScore() == null ? 0 : t.getScore().longValue()))
                .toList();
    }
}

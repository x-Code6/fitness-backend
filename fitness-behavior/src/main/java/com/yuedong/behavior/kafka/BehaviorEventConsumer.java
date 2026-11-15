package com.yuedong.behavior.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.behavior.mapper.BehaviorLogMapper;
import com.yuedong.behavior.model.entity.BehaviorLog;
import com.yuedong.common.constant.MqConstants;
import com.yuedong.common.constant.RedisKeyConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 埋点事件消费者：Kafka → 幂等落库（明细层） + 热度聚合（course_click → ZSet）。
 *
 * <p>防重分两层，职责不同：</p>
 * <ul>
 *   <li><b>Redis 完成标记</b>：只记录"已经成功落库"的事件，挡住绝大多数重复消息，
 *       避免每条重复都打 DB；</li>
 *   <li><b>DB {@code uk_event}</b>：最终防线。Redis 丢了、挂了、key 过期了，重复数据也进不来。</li>
 * </ul>
 *
 * <p>★手动 ack + 完成标记的顺序：</p>
 * <ul>
 *   <li>先查 Redis 完成标记，命中就 ack；没命中再落库；</li>
 *   <li><b>只有落库成功（或 DB 确认重复）之后才写 Redis 标记</b>。
 *       不能先 SETNX 占坑再落库——消费者如果在落库前崩溃，重投会被自己写下的
 *       "已见"标记挡住，消息就永久丢了；</li>
 *   <li>落库异常不写标记，把异常抛给 {@code DefaultErrorHandler} 重试；</li>
 *   <li>重试耗尽后由 {@code DeadLetterPublishingRecoverer} 投到 DLT。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorEventConsumer {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * 去重标记保留 24 小时，覆盖 Kafka 常见重投窗口。
     * <p>★内存和"一天内唯一事件数"成正比：事件量特别大时可以调短，代价是更多重复落到 DB，
     * 但 DB 唯一索引仍是最终防线。</p>
     */
    private static final Duration SEEN_TTL = Duration.ofHours(24);

    /** event_id / event_type / device_id 的长度上限，提前拦住必然写失败的消息 */
    private static final int EVENT_ID_MAX_LEN = 64;
    private static final int EVENT_TYPE_MAX_LEN = 20;
    private static final int DEVICE_ID_MAX_LEN = 64;

    private final BehaviorLogMapper behaviorLogMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    // ★topic 3 分区，3 个消费者线程各吃一个分区；同一分区仍单线程顺序消费。
    //   消费者无状态，DB uk_event + Redis 完成标记保证并发安全。
    @KafkaListener(topics = MqConstants.BEHAVIOR_TOPIC, groupId = "yd-behavior-consumer",
            concurrency = "3")
    public void onMessage(String json, Acknowledgment ack) {
        // ===== 第 1 步：解析并校验。格式错误重试也不会好，抛出后由 error handler 重试并最终进 DLT =====
        Map<String, Object> event = parse(json);
        String eventId = text(event.get("eventId"));
        String eventType = text(event.get("type"));
        String deviceId = text(event.get("deviceId"));
        requireText(eventId, "eventId", EVENT_ID_MAX_LEN);
        requireText(eventType, "type", EVENT_TYPE_MAX_LEN);
        requireText(deviceId, "deviceId", DEVICE_ID_MAX_LEN);

        // 先组装好整行再碰 Redis：字段解析失败的消息不该在 Redis 里留下"已见"标记
        BehaviorLog logRow = toLog(eventId, eventType, deviceId, event);

        // ===== 第 2 步：Redis 完成标记。只挡"已经成功落库"的事件 =====
        if (alreadyProcessed(eventId)) {
            log.debug("埋点重复，跳过: eventId={}", eventId);
            ack.acknowledge();
            return;
        }

        // ===== 第 3 步：落库 + 副作用。DB 唯一索引是最终防线 =====
        try {
            int inserted = behaviorLogMapper.insertIgnoreDuplicate(logRow);
            if (inserted == 0) {
                // Redis 标记丢了 / 多个消费者并发：DB 已经有这一行，说明处理过了
                log.debug("埋点重复（DB 唯一索引拦住）: eventId={}", eventId);
                markProcessed(eventId);
                ack.acknowledge();
                return;
            }

            // 只在"本次真的插入成功"后做副作用，避免重复消息把热度榜抬高
            if ("course_click".equals(eventType) && logRow.getTargetId() != null) {
                incrHotCourse(logRow.getTargetId(), eventId);
            }

            // ★只有落库成功后才写完成标记：落库前崩溃时，重投不会被误判成重复
            markProcessed(eventId);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("埋点落库失败，交给 error handler 重试: eventId={}, type={}",
                    eventId, eventType, e);
            throw e;
        }
    }

    /** 完成标记只读：Redis 不可用时返回 false，降级交给 DB 唯一索引 */
    private boolean alreadyProcessed(String eventId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(seenKey(eventId)));
        } catch (Exception e) {
            log.warn("Redis 完成标记查询失败，降级为 DB 唯一索引: eventId={}, err={}",
                    eventId, e.getMessage());
            return false;
        }
    }

    /**
     * 落库成功后才写；写失败只记日志，DB 已经是真相，重复消息最多多打一次 DB。
     *
     * <p>★本方法依赖"插入已经提交"：消费方法不要加 {@code @Transactional}。
     * 当前 mapper 调用没有外层事务，insert 是自动提交的，提交之后才写标记。
     * 如果以后要给 onMessage 加事务，必须把这里挪到 afterCommit 执行，
     * 否则标记可能先于数据可见，事务回滚后会留下"假完成"标记。</p>
     */
    private void markProcessed(String eventId) {
        try {
            redis.opsForValue().set(seenKey(eventId), "1", SEEN_TTL);
        } catch (Exception e) {
            log.warn("写 Redis 完成标记失败，不影响落库结果: eventId={}, err={}",
                    eventId, e.getMessage());
        }
    }

    /**
     * 热度榜是统计口径，失败不能让明细落库回滚，也不能触发无意义的重试
     * （重试时 DB 已经是重复行，会直接 ack，热度反而补不回来）。
     */
    private void incrHotCourse(Long courseId, String eventId) {
        try {
            String hotKey = RedisKeyConstants.behaviorHotCourse(LocalDate.now().format(MONTH_FMT));
            redis.opsForZSet().incrementScore(hotKey, String.valueOf(courseId), 1);
            redis.expire(hotKey, Duration.ofDays(40));
        } catch (Exception e) {
            log.error("课程热度更新失败，热度榜可能少计（明细已落库）: eventId={}, courseId={}",
                    eventId, courseId, e);
        }
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("埋点消息不是合法 JSON: " + json, e);
        }
    }

    private BehaviorLog toLog(String eventId, String eventType, String deviceId,
                              Map<String, Object> event) {
        BehaviorLog row = new BehaviorLog();
        row.setEventId(eventId);
        row.setEventType(eventType);
        row.setTargetId(longOrNull(event.get("targetId"), "targetId"));
        row.setDeviceId(deviceId);
        row.setUserId(longOrNull(event.get("userId"), "userId"));
        row.setClientTs(longOrNull(event.get("ts"), "ts"));
        Object props = event.get("props");
        if (props != null) {
            try {
                row.setProps(objectMapper.writeValueAsString(props));
            } catch (Exception e) {
                throw new IllegalArgumentException("埋点 props 无法序列化: " + props, e);
            }
        }
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    private void requireText(String value, String field, int maxLen) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("埋点消息缺少 " + field);
        }
        if (value.length() > maxLen) {
            throw new IllegalArgumentException("埋点字段 " + field + " 超过 " + maxLen + " 字符: " + value);
        }
    }

    private Long longOrNull(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("埋点字段 " + field + " 不是数字: " + value, e);
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String seenKey(String eventId) {
        return RedisKeyConstants.behaviorSeen(eventId);
    }
}

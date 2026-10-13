package com.yuedong.course.mq;

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
 * 课程域事件发布器：完课事件 → point 服务消费 +30 积分。
 *
 * <p>消息体直接发 JSON 字符串（而非 Java 对象）：
 * SimpleMessageConverter 对对象默认走 JDK 序列化（有安全隐患且跨语言不友好），
 * 显式 JSON + 手动序列化最透明。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发布完课事件。
     *
     * @param userId    完课用户
     * @param videoId   视频 id
     * @param videoTitle 视频标题（积分明细里展示用）
     */
    public void publishCourseFinished(Long userId, Long videoId, String videoTitle) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", UUID.randomUUID().toString()); // 消费端幂等键
            event.put("userId", userId);
            event.put("videoId", videoId);
            event.put("videoTitle", videoTitle);
            event.put("points", 30); // 完课奖励积分（与 point 服务约定）

            String json = objectMapper.writeValueAsString(event);
            rabbitTemplate.convertAndSend(
                    MqConstants.COURSE_EVENT_EXCHANGE,
                    MqConstants.COURSE_FINISHED_KEY,
                    json);
            log.info("完课事件已发布: userId={}, videoId={}", userId, videoId);
        } catch (Exception e) {
            log.error("完课事件发布失败: userId={}, videoId={}", userId, videoId, e);
            // 发布失败不抛出——进度已落库，可由后续对账任务补偿（演示项目从简）
        }
    }
}

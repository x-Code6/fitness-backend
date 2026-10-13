package com.yuedong.point.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.point.mapper.MemberPointsMapper;
import com.yuedong.point.mapper.PointsLogMapper;
import com.yuedong.point.model.entity.MemberPoints;
import com.yuedong.point.model.entity.PointsLog;
import com.yuedong.point.mq.RabbitConfig;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.yuedong.common.constant.MqConstants;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 完课事件消费者：course 服务发来的"用户完课"事件 → +30 积分。
 *
 * <p>幂等三层保障：</p>
 * <ol>
 *   <li>Redis SETNX eventId（快速拦截重复投递）；</li>
 *   <li>t_points_log.biz_id 唯一约束（Redis 失效后的最终兜底）；</li>
 *   <li>捕获 DuplicateKeyException 视为已处理，正常 ack。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseFinishedConsumer {

    private final MemberPointsMapper memberPointsMapper;
    private final PointsLogMapper pointsLogMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final com.yuedong.point.mq.NotifyPublisher notifyPublisher;

    @RabbitListener(queues = MqConstants.POINT_COURSE_FINISHED_QUEUE)
    public void onMessage(String json, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Map<String, Object> event;
        try {
            event = objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("完课事件解析失败，进死信: {}", json, e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String eventId = String.valueOf(event.get("eventId"));
        Long userId = ((Number) event.get("userId")).longValue();
        String videoTitle = String.valueOf(event.getOrDefault("videoTitle", ""));
        int points = ((Number) event.getOrDefault("points", 30)).intValue();

        try {
            // ===== 第 1 步：Redis 幂等拦截 =====
            Boolean first = redis.opsForValue().setIfAbsent(
                    "idempotent:msg:" + eventId, "1", Duration.ofHours(24));
            if (!Boolean.TRUE.equals(first)) {
                log.info("完课事件重复，跳过: eventId={}", eventId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ===== 第 2 步：确保汇总行存在（首事件的用户可能还没有积分账户） =====
            ensureMember(userId);

            // ===== 第 3 步：记流水（biz_id 唯一约束兜底幂等）+ 加余额 =====
            PointsLog logRow = new PointsLog();
            logRow.setUserId(userId);
            logRow.setTitle("完成跟练：" + videoTitle);
            logRow.setPoints(points);
            logRow.setBizType("COURSE_FINISHED");
            logRow.setBizId(eventId);
            logRow.setCreatedAt(LocalDateTime.now());
            try {
                pointsLogMapper.insert(logRow);
            } catch (DuplicateKeyException e) {
                log.info("完课事件已入账（唯一约束拦截）: eventId={}", eventId);
                channel.basicAck(deliveryTag, false);
                return;
            }
            memberPointsMapper.addEventPoints(userId, points);

            // 站内通知：积分到账
            notifyPublisher.publishPointNotify(userId, "积分到账",
                    "完成跟练「" + videoTitle + "」+" + points + " 积分已到账。");

            channel.basicAck(deliveryTag, false);
            log.info("完课积分已发放: userId={}, points={}", userId, points);
        } catch (Exception e) {
            log.error("完课事件处理失败，进死信: eventId={}", eventId, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /** 汇总行不存在则初始化（并发下靠 user_id 主键兜底） */
    private void ensureMember(Long userId) {
        if (memberPointsMapper.selectById(userId) != null) return;
        MemberPoints init = new MemberPoints();
        init.setUserId(userId);
        init.setBalance(0);
        init.setTotalCheckin(0);
        init.setContinuousDays(0);
        init.setUpdatedAt(LocalDateTime.now());
        try {
            memberPointsMapper.insert(init);
        } catch (DuplicateKeyException ignore) {
        }
    }
}

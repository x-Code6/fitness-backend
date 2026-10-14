package com.yuedong.point.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.constant.MqConstants;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.point.service.PointsTxWriter;
import com.yuedong.point.mq.NotifyPublisher;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 完课事件消费者：course 服务发来的"用户完课"事件 → +30 积分。
 *
 * <p>★<b>这一版改了两件事，都是"看起来能跑但账是错的"</b>：</p>
 * <ol>
 *   <li><b>入账改走 {@link PointsTxWriter}</b>（P0-6）。以前这里自己写一遍
 *       "插流水 + 更余额"，打卡那边写另一遍。两份实现的口径迟早分叉，
 *       而分叉的表现是"同一个用户同样的入账，从不同入口进来结果不一样"。
 *       现在打卡、完课共用同一个事务方法，幂等键也在同一个位置判；</li>
 *   <li><b>入账成功后 ZINCRBY 当月榜</b>（P0-6 的另一半）。以前只有打卡路径动排行榜，
 *       完课的 30 分进了余额却不进榜——榜单与余额<b>静默背离</b>，
 *       而且没有任何日志会说"这里少加了"。</li>
 * </ol>
 *
 * <p>幂等两层：Redis 按 eventId SETNX（快），{@code t_points_log} 的
 * {@code uk_biz(biz_type, biz_id)}（慢但可靠）。★<b>这两层能成立的前提是
 * eventId 是业务键 {@code userId:videoId}</b>——生产端以前发的是每次新生成的 UUID，
 * 于是两层幂等都认为"这是两条不同事件"，完课 30 分可以反复到账（P0-3）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseFinishedConsumer {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final PointsTxWriter pointsTxWriter;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final NotifyPublisher notifyPublisher;

    @RabbitListener(queues = MqConstants.POINT_COURSE_FINISHED_QUEUE)
    public void onMessage(String json, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        // ===== 第 1 步：解析。解析不了的报文重投一百次也还是坏报文 → 直接进死信（requeue=false） =====
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
            // ===== 第 2 步：Redis 先挡一道（省一次 DB 写），真正的防线是下一条里的唯一索引 =====
            Boolean first = redis.opsForValue().setIfAbsent(
                    "idempotent:msg:" + eventId, "1", Duration.ofHours(24));
            if (!Boolean.TRUE.equals(first)) {
                log.info("完课事件重复，跳过: eventId={}", eventId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ===== 第 3 步：入账（流水 + 余额，同一个事务）=====
            // ★biz_id 传 eventId：uk_biz(COURSE_FINISHED, "userId:videoId") 就是数据库级幂等，
            //   Redis 那道挡不住的情况（清空/过期/并发）由它兜住
            boolean granted = pointsTxWriter.grant(userId, points, 0,
                    "COURSE_FINISHED", eventId, "完成跟练：" + videoTitle);
            if (!granted) {
                log.info("完课事件已入账过（唯一索引拦截）: eventId={}", eventId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ===== 第 4 步：入账成功后才动榜和发通知（口径与打卡完全一致）=====
            redis.opsForZSet().incrementScore(
                    RedisKeyConstants.rankMonth(LocalDate.now().format(MONTH_FMT)),
                    String.valueOf(userId), points);
            notifyPublisher.publishPointNotify(userId, "积分到账",
                    "完成跟练「" + videoTitle + "」+" + points + " 积分已到账。");

            channel.basicAck(deliveryTag, false);
            log.info("完课积分已发放: userId={}, points={}", userId, points);
        } catch (Exception e) {
            // ★requeue=false：处理失败的消息如果重新入队，会被立刻再投回来，
            //   一条持续失败的消息能把消费者线程打满（P0-10 的"毒消息"）。
            //   现在这条队列挂了 DLX，失败的消息落在死信队列里，看得见也可以人工重投
            log.error("完课事件处理失败，进死信: eventId={}", eventId, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}

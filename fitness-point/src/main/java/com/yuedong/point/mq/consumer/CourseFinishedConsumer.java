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

    // ★ackMode=MANUAL：自己 ack 就必须声明手动，否则容器 AUTO 会重复 ack，
    //   broker 以 unknown delivery tag 关信道，完课发积分这条链路会反复断流
    @RabbitListener(queues = MqConstants.POINT_COURSE_FINISHED_QUEUE, ackMode = "MANUAL")
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

        // 解析失败的消息重投一百次也还是坏消息 → 直接进死信。
        // 字段读取放在下面的 try 里：缺 userId/类型不对时也应该 nack，而不是逃出监听方法。
        String idempotentKey = null;
        try {
            // 缺字段/类型不对 = 坏消息，直接抛到 catch 里进死信；
            // 不能让 String.valueOf(null) 变成字符串 "null"，否则所有坏消息会共用同一个幂等键。
            Object eventIdValue = event.get("eventId");
            Object userIdValue = event.get("userId");
            if (eventIdValue == null || userIdValue == null) {
                throw new IllegalArgumentException("完课事件缺少 eventId 或 userId");
            }
            String eventId = String.valueOf(eventIdValue);
            Long userId = ((Number) userIdValue).longValue();
            String videoTitle = String.valueOf(event.getOrDefault("videoTitle", ""));
            int points = ((Number) event.getOrDefault("points", 30)).intValue();
            idempotentKey = "idempotent:msg:" + eventId;

            // ===== 第 2 步：Redis 快路径（省一次 DB 写），真正的防线是 t_points_log.uk_biz =====
            // ★这个 key 必须在"入账失败"时删掉，否则重投会被误判成重复、直接 ack。
            Boolean first = redis.opsForValue().setIfAbsent(
                    idempotentKey, "1", Duration.ofHours(24));
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

            // ===== 第 4 步：入账成功后才动榜和发通知 =====
            // ★榜/通知是旁路：失败只记日志，不能把已经成功入账的消息再 nack 重投，
            //   否则消息会进死信，而积分其实已经到账。
            try {
                redis.opsForZSet().incrementScore(
                        RedisKeyConstants.rankMonth(LocalDate.now().format(MONTH_FMT)),
                        String.valueOf(userId), points);
            } catch (Exception e) {
                log.error("完课积分已入账，但月榜加分失败，等待后续对账: userId={}", userId, e);
            }
            notifyPublisher.publishPointNotify(userId, "积分到账",
                    "完成跟练「" + videoTitle + "」+" + points + " 积分已到账。");

            channel.basicAck(deliveryTag, false);
            log.info("完课积分已发放: userId={}, points={}", userId, points);
        } catch (Exception e) {
            // ★入账失败（DB 异常/字段缺失）：删掉快路径 key，让消息重投时还能再试；
            //   requeue=false 让它进死信，避免毒消息立刻回到队头打满消费者线程。
            if (idempotentKey != null) {
                try {
                    redis.delete(idempotentKey);
                } catch (Exception deleteError) {
                    // Redis 也挂了时，不能因为删 key 失败就跳过 nack；
                    // 这条消息进死信，人工重投前再清 key。
                    log.error("完课事件失败后清理幂等 key 失败: key={}", idempotentKey, deleteError);
                }
            }
            log.error("完课事件处理失败，进死信: eventId={}", idempotentKey, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}

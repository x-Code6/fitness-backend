package com.yuedong.order.mq.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.constant.MqConstants;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.api.client.CourseClient;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.SeckillActiveDTO;
import com.yuedong.order.constant.BookingStatus;
import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.mq.RabbitConfig;
import com.yuedong.order.mq.message.SeckillCreateMessage;
import com.yuedong.order.mq.message.OrderTimeoutMessage;
import com.yuedong.order.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.core.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 秒杀建单消费者：排队消息 → 真正创建订单（削峰填谷的核心）。
 *
 * <p>可靠性设计：</p>
 * <ol>
 *   <li>消费幂等：msgId 在 Redis SETNX，重复投递直接 ack 跳过；</li>
 *   <li>手动 ack：处理成功才确认；业务失败（回补库存 + 写失败结果）也 ack；</li>
 *   <li>未知异常：回补库存 + 写失败结果 + nack 进死信队列，事后排查。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillCreateConsumer {

    private final BookingMapper bookingMapper;
    private final CourseClient courseClient;
    private final SeckillService seckillService;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    @RabbitListener(queues = MqConstants.ORDER_SECKILL_CREATE_QUEUE)
    public void onMessage(String json, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        SeckillCreateMessage msg;
        try {
            msg = objectMapper.readValue(json, SeckillCreateMessage.class);
        } catch (Exception e) {
            log.error("秒杀消息解析失败，直接进死信: {}", json, e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        try {
            // ===== 第 1 步：消费幂等（Redis SETNX，重复投递直接跳过） =====
            Boolean first = redis.opsForValue().setIfAbsent(
                    RedisKeyConstants.idempotentMsg(msg.getMsgId()), "1", Duration.ofHours(24));
            if (!Boolean.TRUE.equals(first)) {
                log.warn("秒杀消息重复投递，已跳过: msgId={}", msg.getMsgId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ===== 第 2 步：扣减 DB 库存（Redis 已在 Lua 扣过，这里做最终一致） =====
            var deductResult = courseClient.deductRemain(msg.getActivityId());
            if (deductResult.getCode() != 0 || !Boolean.TRUE.equals(deductResult.getData())) {
                // DB 库存已空但 Redis 还有（极端不一致）：按售罄处理并回补 Redis
                restoreStock(msg);
                seckillService.writeResult(msg.getSeqId(), "FAIL", null,
                        ErrorCode.SECKILL_SOLD_OUT.getMessage());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ===== 第 3 步：查活动与课程信息（两次 Feign 只读调用） =====
            // ★门店来自活动（t_seckill_activity.store_id）而不是课程：v2 里课程不再属于单店。
            //   活动一旦查不到就无法确定这条订单属于哪家店，宁可判失败也不能"按原价兜底"蒙混——
            //   旧实现那个兜底建出来的单没有门店，之后既约不了教练也退不了款。
            var activeResult = courseClient.activeSeckill(msg.getCourseId());
            if (activeResult.getCode() != 0 || activeResult.getData() == null
                    || activeResult.getData().getStoreId() == null) {
                throw new BusinessException(ErrorCode.SECKILL_NOT_ACTIVE, "秒杀活动已结束或门店未配置");
            }
            SeckillActiveDTO active = activeResult.getData();
            Long storeId = active.getStoreId();
            BigDecimal seckillPrice = active.getSeckillPrice();

            var courseResult = courseClient.detail(msg.getCourseId(), storeId);
            if (courseResult.getCode() != 0 || courseResult.getData() == null) {
                throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
            }
            CourseDTO course = courseResult.getData();

            // ===== 第 4 步：创建待支付订单（★没有 book_time：购买不带时间，时间属于预约） =====
            Booking booking = new Booking();
            booking.setOrderNo("B" + System.currentTimeMillis()
                    + ThreadLocalRandom.current().nextInt(100, 1000));
            booking.setUserId(msg.getUserId());
            booking.setCourseId(msg.getCourseId());
            booking.setCourseKind(course.getCourseKind());
            booking.setActivityId(msg.getActivityId());
            booking.setStoreId(storeId);
            booking.setCourseName(course.getName());
            booking.setStoreName(course.getStoreName() == null ? "门店未配置" : course.getStoreName());
            booking.setLessonHours(course.getLessonHours());
            booking.setSaleType(course.getSaleType());
            booking.setTimesGranted("UNLIMITED".equals(course.getSaleType()) ? null : 1);
            booking.setQuantity(1);
            // 原价小计存课程原价、实付存秒杀价：详情页要显示划线原价，这两个不能合并成一个字段
            booking.setOriginalAmount(course.getPrice());
            booking.setDiscountAmount(course.getPrice().subtract(seckillPrice).max(java.math.BigDecimal.ZERO));
            booking.setPayAmount(seckillPrice);
            booking.setStatus(BookingStatus.UNPAID);
            booking.setRefunded(0);
            bookingMapper.insert(booking);

            // ===== 第 5 步：同样发 15 分钟延迟取消消息 =====
            OrderTimeoutMessage timeout = new OrderTimeoutMessage(booking.getOrderNo(), msg.getUserId());
            rabbitTemplate.convertAndSend(
                    MqConstants.ORDER_DELAY_EXCHANGE,
                    MqConstants.ORDER_TIMEOUT_KEY,
                    objectMapper.writeValueAsString(timeout),
                    m -> {
                        m.getMessageProperties().setDelay(15 * 60 * 1000);
                        return m;
                    });

            // ===== 第 6 步：写成功结果 + ack（前端轮询拿到订单号） =====
            seckillService.writeResult(msg.getSeqId(), "SUCCESS", booking.getOrderNo(), null);
            log.info("秒杀建单成功: orderNo={}, userId={}, activity={}",
                    booking.getOrderNo(), msg.getUserId(), msg.getActivityId());
            channel.basicAck(deliveryTag, false);

        } catch (BusinessException be) {
            // ===== 业务失败：回补 Redis 库存 + 写失败结果 + ack（无需重投） =====
            restoreStock(msg);
            seckillService.writeResult(msg.getSeqId(), "FAIL", null, be.getMessage());
            log.warn("秒杀建单业务失败: seqId={}, reason={}", msg.getSeqId(), be.getMessage());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // ===== 未知异常：回补库存 + 写失败结果 + nack 进死信队列 =====
            restoreStock(msg);
            seckillService.writeResult(msg.getSeqId(), "FAIL", null, "系统繁忙，请重试");
            log.error("秒杀建单未知异常: seqId={}", msg.getSeqId(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /** 失败路径回补 Redis 库存与购买资格（两次独立操作，失败路径可接受非原子） */
    private void restoreStock(SeckillCreateMessage msg) {
        redis.opsForValue().increment(RedisKeyConstants.seckillStock(msg.getActivityId()));
        redis.opsForSet().remove(RedisKeyConstants.seckillBought(msg.getActivityId()), String.valueOf(msg.getUserId()));
    }
}

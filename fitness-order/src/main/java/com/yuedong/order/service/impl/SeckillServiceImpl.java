package com.yuedong.order.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.constant.MqConstants;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.api.client.CourseClient;
import com.yuedong.course.api.dto.SeckillActiveDTO;
import com.yuedong.order.model.vo.SeckillBookVO;
import com.yuedong.order.model.vo.SeckillResultVO;
import com.yuedong.order.mq.RabbitConfig;
import com.yuedong.order.mq.message.SeckillCreateMessage;
import com.yuedong.order.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 秒杀实现：Lua 原子校验 → MQ 异步建单 → Redis 结果轮询。
 *
 * <p>为什么快：接口本身不做任何 DB 操作，只做"Redis Lua（内存级）+ 发一条 MQ 消息"，
 * 单机数千 QPS 无压力；订单创建由消费者排队完成，把 DB 写压力从瞬时尖峰摊平成平稳消费。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private static final String LUA_PATH = "lua/seckill.lua";

    private final StringRedisTemplate redis;
    private final CourseClient courseClient;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /** Lua 脚本：启动时加载一次（脚本会被 Redis 缓存，重复执行不重复传输） */
    private final DefaultRedisScript<Long> seckillScript = buildScript();

    private DefaultRedisScript<Long> buildScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(LUA_PATH));
        script.setResultType(Long.class);
        return script;
    }

    // =====================================================================
    // 抢课：入口（同步部分）
    // =====================================================================
    @Override
    public SeckillBookVO book(Long userId, Long courseId) {
        // ===== 第 1 步：查课程进行中的秒杀活动（Feign） =====
        var activeResult = courseClient.activeSeckill(courseId);
        if (activeResult.getCode() != 0 || activeResult.getData() == null) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_ACTIVE);
        }
        SeckillActiveDTO active = activeResult.getData();

        // ===== 第 2 步：预写排队结果（前端立刻能轮询到 QUEUEING） =====
        String seqId = UUID.randomUUID().toString();
        writeResult(seqId, "QUEUEING", null, null);

        // ===== 第 3 步：Lua 原子校验"库存 + 一人一单"并扣减 =====
        // 资格集合 TTL = 距活动结束的秒数（活动结束 Redis 自动清理，不留垃圾 key）
        long ttlSeconds = Math.max(60, (active.getEndTime() - System.currentTimeMillis()) / 1000);
        Long code = redis.execute(seckillScript,
                List.of(RedisKeyConstants.seckillStock(active.getActivityId()), RedisKeyConstants.seckillBought(active.getActivityId())),
                String.valueOf(userId), String.valueOf(ttlSeconds));

        // ===== 第 4 步：Lua 失败码 → 直接写失败结果并抛业务异常（不进队列） =====
        if (code != null && code == 1L) {
            writeResult(seqId, "FAIL", null, ErrorCode.SECKILL_SOLD_OUT.getMessage());
            throw new BusinessException(ErrorCode.SECKILL_SOLD_OUT);
        }
        if (code != null && code == 2L) {
            writeResult(seqId, "FAIL", null, ErrorCode.SECKILL_DUPLICATE.getMessage());
            throw new BusinessException(ErrorCode.SECKILL_DUPLICATE);
        }

        // ===== 第 5 步：发送建单消息（消费端真正落库，削峰填谷） =====
        try {
            SeckillCreateMessage message = new SeckillCreateMessage(
                    UUID.randomUUID().toString(), seqId, userId,
                    active.getActivityId(), active.getCourseId());
            rabbitTemplate.convertAndSend(
                    MqConstants.ORDER_SECKILL_EXCHANGE,
                    MqConstants.ORDER_SECKILL_CREATE_KEY,
                    objectMapper.writeValueAsString(message),
                    msg -> {
                        msg.getMessageProperties().setMessageId(message.getMsgId());
                        return msg;
                    });
            log.info("秒杀请求已入队: seqId={}, userId={}, activityId={}", seqId, userId, active.getActivityId());
        } catch (Exception e) {
            // 消息没发出去 = Redis 库存已扣但订单永远不会建 → 必须回补（非原子，失败路径可接受）
            redis.opsForValue().increment(RedisKeyConstants.seckillStock(active.getActivityId()));
            redis.opsForSet().remove(RedisKeyConstants.seckillBought(active.getActivityId()), String.valueOf(userId));
            writeResult(seqId, "FAIL", null, "系统繁忙，请重试");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙，请重试");
        }

        return new SeckillBookVO(seqId);
    }

    // =====================================================================
    // 轮询结果
    // =====================================================================
    @Override
    public SeckillResultVO result(String seqId) {
        String value = redis.opsForValue().get(RedisKeyConstants.seckillResult(seqId));
        if (value == null) {
            // 结果 key 过期（10 分钟）仍未成功，按失败兜底
            return new SeckillResultVO("FAIL", null, "排队超时，请重试");
        }
        if (value.startsWith("SUCCESS:")) {
            return new SeckillResultVO("SUCCESS", value.substring("SUCCESS:".length()), null);
        }
        if (value.startsWith("FAIL:")) {
            return new SeckillResultVO("FAIL", null, value.substring("FAIL:".length()));
        }
        return new SeckillResultVO("QUEUEING", null, null);
    }

    /** {@inheritDoc} */
    @Override
    public void writeResult(String seqId, String status, String orderNo, String reason) {
        String value = switch (status) {
            case "SUCCESS" -> "SUCCESS:" + orderNo;
            case "FAIL" -> "FAIL:" + reason;
            default -> "QUEUEING";
        };
        redis.opsForValue().set(RedisKeyConstants.seckillResult(seqId), value, Duration.ofMinutes(10));
    }
}

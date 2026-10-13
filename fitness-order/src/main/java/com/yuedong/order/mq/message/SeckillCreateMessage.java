package com.yuedong.order.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀建单消息（订单服务内部：生产者 SeckillService → 消费者 SeckillCreateConsumer）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillCreateMessage {

    /** 消息唯一 id：消费端幂等键（重复投递时 SETNX 拦截） */
    private String msgId;

    /** 排队号（与 Redis 结果 Key 对应：seckill:result:{seqId}） */
    private String seqId;

    private Long userId;

    private Long activityId;

    private Long courseId;
}

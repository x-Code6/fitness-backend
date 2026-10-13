package com.yuedong.order.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 秒杀下单结果：排队号（前端拿它轮询 /seckill/result）。
 */
@Data
@AllArgsConstructor
public class SeckillBookVO {

    /** 排队号（UUID，结果缓存在 Redis：seckill:result:{seqId}） */
    private String seqId;
}

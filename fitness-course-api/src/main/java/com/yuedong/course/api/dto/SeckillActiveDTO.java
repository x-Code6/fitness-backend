package com.yuedong.course.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 进行中的秒杀活动 DTO（订单服务秒杀下单时 Feign 获取）。
 */
@Data
@Builder
public class SeckillActiveDTO {

    /** 活动 id（Redis 库存/购买资格 Key 的组成部分） */
    private Long activityId;

    private Long courseId;

    private BigDecimal seckillPrice;

    /** 活动结束时间戳（毫秒）：订单服务计算 Redis 资格集合 TTL 用 */
    private Long endTime;
}

package com.yuedong.course.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 进行中的秒杀活动 DTO（订单服务秒杀下单时 Feign 获取）。
 *
 * <p>★跨服务 DTO 必须带无参构造：只有 {@code @Builder} 时消费端 Jackson 无法实例化，
 * 表现是接口 200 但 data 反序列化为 null。之前这个类就缺，
 * 因为 order 侧还没真正跑过这条 Feign 链路，所以一直没暴露。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillActiveDTO {

    /** 活动 id（Redis 库存/购买资格 Key 的组成部分） */
    private Long activityId;

    private Long courseId;

    /**
     * 专场所属门店 id。
     * <p>★订单侧建单必须拿到这个值：凭证认店，之后只能在该店约教练。
     * 之前这个字段缺失，所以秒杀建单只能从课程表借一个 store_id（v2 已删该列）。</p>
     */
    private Long storeId;

    private BigDecimal seckillPrice;

    /** 活动结束时间戳（毫秒）：订单服务计算 Redis 资格集合 TTL 用 */
    private Long endTime;
}

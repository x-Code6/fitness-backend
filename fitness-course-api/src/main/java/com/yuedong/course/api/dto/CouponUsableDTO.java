package com.yuedong.course.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 用户持券可用性校验结果 DTO（订单服务下单前 Feign 调用）。
 */
@Data
@Builder
public class CouponUsableDTO {

    /** 持券记录 id */
    private Long recordId;

    /** 券名（订单备注/取消回退展示） */
    private String title;

    /** 抵扣金额 */
    private BigDecimal discount;

    /** 是否可用（属于该用户 + UNUSED + 未过期 + 达到门槛） */
    private boolean usable;

    /** 不可用原因 */
    private String reason;
}

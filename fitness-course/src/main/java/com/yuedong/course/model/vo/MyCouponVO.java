package com.yuedong.course.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 我的持券条目（预约时选券/我的券列表）。
 */
@Data
@Builder
public class MyCouponVO {

    /** 持券记录 id（下单时传这个，不是券 id） */
    private Long recordId;

    private String title;

    /** 使用门槛（满 X 元） */
    private BigDecimal threshold;

    /** 抵扣金额 */
    private BigDecimal discount;

    /** UNUSED / USED */
    private String status;

    /** 使用截止时间（毫秒） */
    private Long endTime;

    /** 当前是否可用（未使用 + 未过期） */
    private boolean usable;
}

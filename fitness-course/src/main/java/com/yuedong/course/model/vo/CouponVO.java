package com.yuedong.course.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 优惠券视图对象（领券中心/管理端列表共用）。
 */
@Data
@Builder
public class CouponVO {

    private Long id;

    private String title;

    /** 使用门槛（满 X 元） */
    private BigDecimal threshold;

    /** 抵扣金额 */
    private BigDecimal discount;

    private Integer total;

    private Integer remain;

    /** 领取截止（毫秒时间戳，前端倒计时可用） */
    private Long endTime;

    private Integer status;

    /** 当前用户是否已领取（未登录恒 false） */
    private boolean receivedByMe;
}

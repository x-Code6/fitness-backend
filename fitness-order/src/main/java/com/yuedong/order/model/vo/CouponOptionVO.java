package com.yuedong.order.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 下单页上的一张可选券（由课程侧的批量试算结果转来）。
 *
 * <p>★这个类<b>不做任何判断</b>，只是把 {@code CouponUsableDTO} 换个面向界面的外壳
 * （加两个带 ¥ 的文本字段）。判断结果 usable/reason 必须在课程侧产生，
 * 一旦这里出现"我再算一遍能不能用"，规则就有了第二份实现。</p>
 */
@Data
@Builder
public class CouponOptionVO {

    /** 持券记录 id：提交下单时要回传的就是这个值 */
    private Long recordId;

    /** 券模板 id */
    private Long couponId;

    private String title;

    /** SECKILL / NORMAL。秒杀单不能用普通券，前端据此置灰 */
    private String couponType;

    /** 使用门槛（满多少可用），0 = 无门槛 */
    private BigDecimal threshold;

    /** 门槛文案，如"无门槛"或"满 100 可用" */
    private String thresholdText;

    /** 预计抵扣金额 */
    private BigDecimal discount;

    /** 抵扣文案，如"-¥10" */
    private String discountText;

    /** 在当前这份数与这家店下能不能用 */
    private Boolean usable;

    /** 不能用的原因（人话）。可用时为 null */
    private String reason;
}

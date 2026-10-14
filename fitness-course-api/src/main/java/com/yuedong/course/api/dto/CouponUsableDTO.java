package com.yuedong.course.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 用券校验结果（order 服务下单前经 Feign 调用）。
 *
 * <p>★<b>不可用一定要给 reason</b>：只返回 false，前端只能显示"优惠券不可用"，
 * 用户不知道自己踩的是哪一条（没到门槛 / 已过期 / 不是这门课能用 / 这家店不能用）。
 * 这一句话是券业务里客服量最大的来源。</p>
 *
 * <p>注意这里"不可用"<b>不是错误</b>：接口照样返回 {@code code=0} + {@code usable=false}，
 * 因为"这张券不能用"是一个正常的业务答案，不是系统故障。
 * 把它做成异常抛出去，调用方就得用 try/catch 接一个业务分支。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponUsableDTO {

    /** 持券记录 id */
    private Long recordId;

    /** 券模板 id */
    private Long couponId;

    private String title;

    /** SECKILL / NORMAL。★订单侧要据此判断"秒杀单能不能用这张券" */
    private String couponType;

    /** 使用门槛，便于调用方在报错文案里带上"满 X 可用" */
    private BigDecimal threshold;

    /** 抵扣金额 */
    private BigDecimal discount;

    /** 能不能用在这笔订单上 */
    private boolean usable;

    /** 不能用的原因（人话）。可用时为 null */
    private String reason;
}

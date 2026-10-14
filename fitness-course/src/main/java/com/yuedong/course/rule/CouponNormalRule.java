package com.yuedong.course.rule;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 普通券规则说明（D9：不限量、默认不限领，靠"一单一张"天然限制刷券）。
 *
 * <p>★为什么普通券不限领也不可怕（他推翻我"有刷券风险"那条的理由）：
 * 一张订单只能用一张券，多领的券并没有换来更多优惠，
 * 领十张只是占了自己卡包。为此加风控反而是给演示项目添复杂度。</p>
 */
@Component
public class CouponNormalRule implements RuleDescribeStrategy {

    @Override
    public String scene() {
        return RuleScene.COUPON_NORMAL.name();
    }

    @Override
    public List<String> describe(RuleContext context) {
        List<String> texts = new ArrayList<>();
        texts.add("不限量领取，随时可领");
        texts.add(context.getThreshold() != null && context.getThreshold().signum() > 0
                ? "满 " + RuleTextSupport.money(context.getThreshold()) + " 元可抵扣 "
                + RuleTextSupport.money(context.getDiscount()) + " 元"
                : "无门槛立减 " + RuleTextSupport.money(context.getDiscount()) + " 元");
        if (context.getLimitPerUser() != null && context.getLimitPerUser() > 0) {
            // 管理员也可能给普通券设限领，这句话必须按实际配置出，不能写死"不限领"
            texts.add("每人最多领 " + context.getLimitPerUser() + " 张");
        }
        texts.add(RuleTextConstants.ONE_COUPON_PER_ORDER);
        if (context.getCouponValidDays() != null && context.getCouponValidDays() > 0) {
            texts.add("领后 " + context.getCouponValidDays() + " 天内有效");
        }
        texts.add(CouponSeckillRule.scopeText(context));
        return texts;
    }

    @Override
    public String summary(RuleContext context) {
        return context.getThreshold() != null && context.getThreshold().signum() > 0
                ? "满 " + RuleTextSupport.money(context.getThreshold()) + " 减 "
                + RuleTextSupport.money(context.getDiscount())
                : "无门槛减 " + RuleTextSupport.money(context.getDiscount());
    }
}

package com.yuedong.course.rule;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀券规则说明（D9：限量 + 限每人领 N 张）。
 */
@Component
public class CouponSeckillRule implements RuleDescribeStrategy {

    @Override
    public String scene() {
        return RuleScene.COUPON_SECKILL.name();
    }

    @Override
    public List<String> describe(RuleContext context) {
        List<String> texts = new ArrayList<>();
        // ★限量信息放第一句：秒杀券用户最先关心的就是"还剩几个"
        if (context.getTotal() != null) {
            texts.add("限量 " + context.getTotal() + " 份"
                    + (context.getRemain() == null ? "" : "，当前剩 " + context.getRemain() + " 份")
                    + "，领完即止");
        }
        texts.add(context.getThreshold() != null && context.getThreshold().signum() > 0
                ? "满 " + RuleTextSupport.money(context.getThreshold()) + " 元可抵扣 "
                + RuleTextSupport.money(context.getDiscount()) + " 元"
                : "无门槛立减 " + RuleTextSupport.money(context.getDiscount()) + " 元");
        if (context.getLimitPerUser() != null && context.getLimitPerUser() > 0) {
            texts.add("每人最多领 " + context.getLimitPerUser() + " 张");
        }
        texts.add(RuleTextConstants.ONE_COUPON_PER_ORDER);
        if (context.getCouponValidDays() != null && context.getCouponValidDays() > 0) {
            texts.add("领后 " + context.getCouponValidDays() + " 天内有效");
        }
        texts.add(scopeText(context));
        return texts;
    }

    @Override
    public String summary(RuleContext context) {
        return "秒杀专享 · 限量"
                + (context.getRemain() == null ? "" : "，剩 " + context.getRemain() + " 张");
    }

    /** 范围那句话两种券都要说，写在券策略里而不是公共常量：措辞随券类型略有差别 */
    static String scopeText(RuleContext context) {
        Integer scope = context.getScope();
        if (scope == null || scope == 0) {
            return "全场课程可用";
        }
        return scope == 1 ? "仅限指定课程可用" : "仅限指定门店可用";
    }
}

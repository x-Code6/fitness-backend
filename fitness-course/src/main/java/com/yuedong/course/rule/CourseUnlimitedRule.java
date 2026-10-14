package com.yuedong.course.rule;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 不限次课程（{@code sale_type=UNLIMITED}）的规则说明。
 *
 * <p>★和按次课分开写而不是共用一个类加两个 if：这两种形态<b>要强调的点完全不同</b>
 * （按次要讲"还剩几次"，不限次要讲"有效期内的次数规则"），
 * 混在一个类里最后必然变成一堆条件分支——那正是策略模式要替换掉的东西。</p>
 */
@Component
public class CourseUnlimitedRule implements RuleDescribeStrategy {

    @Override
    public String scene() {
        return RuleScene.COURSE_UNLIMITED.name();
    }

    @Override
    public List<String> describe(RuleContext context) {
        List<String> texts = new ArrayList<>();
        texts.add(RuleTextSupport.hoursText(context));
        texts.add("有效期内不限次预约，每次占用一节");
        texts.add("同一时段每位教练仅接待一位会员，约满即止");
        texts.add(RuleTextConstants.CANCEL_NOT_CONSUME + "；" + RuleTextConstants.DUE_TIME_CONSUME);
        texts.add("距开课 1 小时以内不可取消，如需更改请联系门店");
        String valid = RuleTextSupport.validText(context);
        // 不限次课最常见的设计就是 FOREVER（常年日常价），这里为空也要能出话
        texts.add(valid == null ? "凭证有效期以门店告知为准" : valid);
        texts.add(RuleTextConstants.ONE_COUPON_PER_ORDER);
        return texts;
    }

    @Override
    public String summary(RuleContext context) {
        StringBuilder text = new StringBuilder(RuleTextSupport.hours(context) + " 小时/节 · 不限次");
        if ("FOREVER".equals(context.getValidType())) {
            text.append(" · 长期有效");
        } else if (context.getValidType() != null) {
            text.append(" · ").append(RuleTextSupport.validText(context));
        }
        return text.toString();
    }
}

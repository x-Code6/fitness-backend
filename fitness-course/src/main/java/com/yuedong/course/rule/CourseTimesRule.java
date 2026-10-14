package com.yuedong.course.rule;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按次售卖课程（{@code sale_type=TIMES}）的规则说明。
 */
@Component
public class CourseTimesRule implements RuleDescribeStrategy {

    @Override
    public String scene() {
        return RuleScene.COURSE_TIMES.name();
    }

    @Override
    public List<String> describe(RuleContext context) {
        List<String> texts = new ArrayList<>();
        texts.add(RuleTextSupport.hoursText(context));
        texts.add(RuleTextSupport.timesText(context));
        // ★两句成对出现：取消不扣 + 到点算消费。只讲前一句用户会以为可以无限约了再取消
        texts.add(RuleTextConstants.CANCEL_NOT_CONSUME + "；" + RuleTextConstants.DUE_TIME_CONSUME);
        texts.add("距开课 1 小时以内不可取消，如需更改请联系门店");
        String valid = RuleTextSupport.validText(context);
        if (valid != null) {
            texts.add(valid);
        }
        texts.add(RuleTextConstants.ONE_COUPON_PER_ORDER);
        return texts;
    }

    @Override
    public String summary(RuleContext context) {
        // 列表页只给用户决策用得上的三个数字：几小时一节、一单几次、有效期
        StringBuilder text = new StringBuilder();
        text.append(RuleTextSupport.hours(context)).append(" 小时/节 · 一次 ")
                .append(RuleTextSupport.times(context)).append(" 次");
        if (context.getValidType() != null && "DAYS".equals(context.getValidType())
                && context.getValidDays() != null) {
            text.append(" · 领后 ").append(context.getValidDays()).append(" 天");
        } else if ("FOREVER".equals(context.getValidType())) {
            text.append(" · 长期有效");
        } else if ("RANGE".equals(context.getValidType())) {
            text.append(" · 至 ").append(RuleTextSupport.day(context.getUseEndTime()));
        }
        return text.toString();
    }
}

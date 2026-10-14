package com.yuedong.course.rule;

import java.time.format.DateTimeFormatter;

/**
 * 策略之间共用的小工具（<b>不是策略，也不对外</b>）。
 *
 * <p>★为什么不做成抽象基类：五个策略需要的共用东西只有两句话术拼接逻辑，
 * 用继承会把"类型层次"骗出来（"CourseTimesRule 是一个 RuleTextSupport"？说不通）。
 * 一个包内的静态工具类，读的人一眼就知道它只是帮忙算文案。</p>
 */
final class RuleTextSupport {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private RuleTextSupport() {
    }

    /**
     * 有效期那句话。三种 valid_type 读三组列，★不做"都填取交集"的隐式规则。
     * <p>context 里没有门店上下文时（列表页）validType 为 null，
     * 此时这句直接返回 null，由调用方决定省略——而不是猜一个"长期有效"骗用户。</p>
     */
    static String validText(RuleContext context) {
        if (context.getValidType() == null) {
            return null;
        }
        switch (context.getValidType()) {
            // FOREVER：常年日常价课，买了就一直能约
            case "FOREVER":
                return "凭证长期有效，不过期";
            case "DAYS":
                if (context.getValidDays() == null || context.getValidDays() < 1) {
                    // 管理员配了 DAYS 却没填天数：说人话承认没配好，不要显示"支付后 null 天"
                    return "凭证有效期未配置，请联系门店";
                }
                return "支付后 " + context.getValidDays() + " 天内可用，过期未用完的次数不退";
            case "RANGE":
                if (context.getUseEndTime() == null) {
                    return "凭证有效期未配置，请联系门店";
                }
                return "可在 " + context.getUseEndTime().format(DAY_FMT) + " 之前使用";
            default:
                return "凭证有效期以门店告知为准";
        }
    }

    /** 次数那句话：TIMES 说"一单几次"，UNLIMITED 说"随便约" */
    static String timesText(RuleContext context) {
        if ("UNLIMITED".equals(context.getSaleType())) {
            return "有效期内不限次预约，每次占用一节";
        }
        int times = context.getTimesPerBuy() == null || context.getTimesPerBuy() < 1
                ? 1 : context.getTimesPerBuy();
        return "一次购买得 " + times + " 次，一次预约消耗 1 次";
    }

    /** 节时长那句话；lessonHours 缺失时按 1 兜底（表单是下拉框，正常不会缺） */
    static String hoursText(RuleContext context) {
        int hours = context.getLessonHours() == null || context.getLessonHours() < 1
                ? 1 : context.getLessonHours();
        return hours + " 小时/节，一次预约占用 " + hours + " 个整点时段，不可拆分";
    }

    static int hours(RuleContext context) {
        return context.getLessonHours() == null || context.getLessonHours() < 1
                ? 1 : context.getLessonHours();
    }

    static int times(RuleContext context) {
        return context.getTimesPerBuy() == null || context.getTimesPerBuy() < 1
                ? 1 : context.getTimesPerBuy();
    }

    /** 金额显示：给整数就别带小数（"满 100 减 20" 比 "满 100.00 可用" 更像人话） */
    static String money(java.math.BigDecimal amount) {
        if (amount == null) {
            return "0";
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    /** 日期串，null 给空串而不是 "null" */
    static String day(java.time.LocalDateTime time) {
        return time == null ? "" : time.format(DAY_FMT);
    }
}

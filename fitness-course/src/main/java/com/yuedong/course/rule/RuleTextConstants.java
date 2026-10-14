package com.yuedong.course.rule;

/**
 * 跨场景重复出现的规则话术，<b>只写这一份</b>。
 *
 * <p>★为什么值得单独一个类：同一句"取消预约不扣次数"会出现在课程详情页、
 * 时段页规则弹窗、券详情页三处。三处各写一遍，改一处留两处，
 * 用户就会在不同页面看到互相矛盾的规则——这类不一致比文案难看严重得多，
 * 因为它让用户怀疑整个系统。</p>
 *
 * <p>这里只放<b>话术</b>，不放业务参数：1 小时、15 分钟这些数字各服务有自己的常量
 * （{@code OrderConstants.CANCEL_DEADLINE_HOURS}），文案里要出现数字时由调用方拼进去，
 * 免得这句话术和真正的判断逻辑各自跑。</p>
 */
public final class RuleTextConstants {

    /** 券的通用限制。出现在课程页、券页、结算页 */
    public static final String ONE_COUPON_PER_ORDER = "一单只能使用一张优惠券";

    /** 取消预约的后果。出现在课程页、时段页、预约详情页 */
    public static final String CANCEL_NOT_CONSUME = "取消预约不扣次数，时段自动释放";

    /** 扣次时机（D5）。和上一句永远一起出现，所以成对写 */
    public static final String DUE_TIME_CONSUME = "到点未取消即视为消费一次";

    /** 不可拆分的时段占用规则（D3） */
    public static final String ONE_CLASS_ONE_SLOT = "一次预约消耗一节，不可拆分";

    private RuleTextConstants() {
    }
}

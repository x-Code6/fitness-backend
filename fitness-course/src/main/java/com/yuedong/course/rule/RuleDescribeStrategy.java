package com.yuedong.course.rule;

import java.util.List;

/**
 * 规则说明生成策略（D21）。
 *
 * <p><b>解决的问题</b>：按次课要讲"几次/怎么用/过期怎么办"，不限次课要讲"随便约"，
 * 秒杀券要讲"限量/限领"，展示课只讲"免费跟练"。如果写成
 * {@code if (saleType==...) else if (couponType==...)}，六个场景每个四到六行文案，
 * 最后会是一个三十多个分支、谁加一种类型都要改同一个方法的大函数。</p>
 *
 * <p><b>本项目的取舍</b>：一个接口 + 按场景码注册的实现类，
 * <b>不做规则引擎、不落库、不让运营写文案表达式</b>。
 * 文案要能被 review、能被单测、能跟着代码一起回滚——
 * "后台自己配文案"听起来灵活，实际是把一个 {@code List<String>} 需求做成解释器。</p>
 */
public interface RuleDescribeStrategy {

    /**
     * 本策略负责的场景码，取值见 {@link RuleScene}。
     * <p>★用字符串而不是枚举返回值：注册时按 {@code RuleScene.name()} 建 Map，
     * 实现类之间不互相依赖枚举实例的加载顺序，也方便将来按配置注册新场景。</p>
     */
    String scene();

    /**
     * 整块规则条目（详情页/弹层直接渲染成列表）。
     * <p>每个元素是一行完整文案，前端不再拼任何字段。</p>
     */
    List<String> describe(RuleContext context);

    /**
     * 一句话摘要（列表页用）。
     * <p>★和 {@link #describe} 放在同一个策略里而不是另写一个方法，是为了让
     * "摘要"与"全文"永远由同一处产出——分开实现迟早会出现摘要说有 3 次、
     * 详情里写 2 次这种自相矛盾。</p>
     */
    String summary(RuleContext context);
}

package com.yuedong.course.rule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则说明工厂：按场景码选实现。
 *
 * <p>注册方式是 Spring 直接把所有 {@link RuleDescribeStrategy} 实现注入成 List，
 * 再按 {@code scene()} 转 Map。<b>新增一种课程/券形态 = 新写一个 @Component 类，
 * 本工厂一行都不用改</b>——这才是策略模式相对大 switch 的真实收益。</p>
 *
 * <p>★找不到实现时退回兜底文案并打 warn，而不是返回空列表或抛异常：
 * 空列表在页面上表现为"规则弹窗一片空白"，用户以为自己没权限；
 * 抛异常会让详情页整个打不开。兜底文案 + 日志留痕，
 * 既有东西可看，又不会让"忘了注册实现"这件事静默过去。</p>
 */
@Slf4j
@Component
public class RuleDescribeFactory {

    private final Map<String, RuleDescribeStrategy> strategyMap;

    public RuleDescribeFactory(List<RuleDescribeStrategy> strategies) {
        this.strategyMap = new HashMap<>();
        for (RuleDescribeStrategy strategy : strategies) {
            RuleDescribeStrategy previous = this.strategyMap.put(strategy.scene(), strategy);
            if (previous != null) {
                // 同一个场景码注册了两个实现 = 两个类都以为自己是唯一解释者，
                // 后加载的会盖掉前一个，表现随启动顺序变化，必须当场暴露
                log.error("规则策略场景码重复: scene={}, 已被 {} 占用，本次被 {} 覆盖",
                        strategy.scene(), previous.getClass().getSimpleName(),
                        strategy.getClass().getSimpleName());
            }
        }
    }

    /** 整块规则条目 */
    public List<String> describe(String scene, RuleContext context) {
        RuleDescribeStrategy strategy = pick(scene);
        if (strategy == null) {
            return fallback();
        }
        return strategy.describe(context);
    }

    /** 列表用一句话摘要 */
    public String summary(String scene, RuleContext context) {
        RuleDescribeStrategy strategy = pick(scene);
        if (strategy == null) {
            return "具体规则以门店告知为准";
        }
        return strategy.summary(context);
    }

    /**
     * 课程场景码：<b>先看是不是展示课，再看售卖形态</b>。
     * <p>★顺序不能反：CONTENT 课的 sale_type 是 null，
     * 若先按 saleType 判断会掉进 TIMES 分支，给展示课写出"一次购买得 1 次"。</p>
     */
    public String courseSceneOf(String courseKind, String saleType) {
        if ("CONTENT".equals(courseKind)) {
            return RuleScene.COURSE_CONTENT.name();
        }
        return "UNLIMITED".equals(saleType)
                ? RuleScene.COURSE_UNLIMITED.name() : RuleScene.COURSE_TIMES.name();
    }

    /** 券场景码 */
    public String couponSceneOf(String couponType) {
        return "SECKILL".equals(couponType)
                ? RuleScene.COUPON_SECKILL.name() : RuleScene.COUPON_NORMAL.name();
    }

    private RuleDescribeStrategy pick(String scene) {
        if (scene == null) {
            return null;
        }
        RuleDescribeStrategy strategy = strategyMap.get(scene);
        if (strategy == null) {
            log.warn("没有注册场景 {} 的规则策略，已退回兜底文案（新增形态时记得写实现类）", scene);
        }
        return strategy;
    }

    private List<String> fallback() {
        List<String> texts = new ArrayList<>();
        texts.add("具体使用规则以门店告知为准");
        texts.add(RuleTextConstants.ONE_COUPON_PER_ORDER);
        return texts;
    }
}

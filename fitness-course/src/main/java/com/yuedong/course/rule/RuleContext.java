package com.yuedong.course.rule;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 规则文案的<b>输入</b>：调用方把已经查好的字段填进来，策略只负责把它们说成人话。
 *
 * <p>★三条不许越过的线（docs 4.3.5，违反任何一条这个模式就会烂掉）：</p>
 * <ol>
 *   <li><b>策略不查库</b>。谁调用谁负责取数，本对象就是那次取数的结果载体。
 *       一旦策略里出现 Mapper，"改文案"就等于"改查询"，回归范围立刻失控；</li>
 *   <li><b>策略不校验</b>。"能不能用这张券""能不能约这个时间"的判断留在各 Service 里。
 *       把校验塞进策略，将来改一句文案会连带改掉业务规则——这是最难查的一类 bug；</li>
 *   <li><b>字段允许为 null</b>。列表页没有门店上下文就填不出有效期，
 *       所以每个策略都要能在一半字段缺失时正常出话，不许抛 NPE。</li>
 * </ol>
 */
@Data
@Builder
public class RuleContext {

    // ---------- 课程侧字段 ----------

    /** SALE / CONTENT */
    private String courseKind;

    /** TIMES / UNLIMITED */
    private String saleType;

    /** 一节课几小时 */
    private Integer lessonHours;

    /** 买一单给几次（TIMES） */
    private Integer timesPerBuy;

    /** FOREVER / DAYS / RANGE；来自 t_course_store，列表页无门店上下文时为 null */
    private String validType;

    private Integer validDays;

    private LocalDateTime useEndTime;

    // ---------- 券侧字段 ----------

    /** SECKILL / NORMAL */
    private String couponType;

    /** 使用门槛，0 = 无门槛 */
    private BigDecimal threshold;

    /** 抵扣金额 */
    private BigDecimal discount;

    /** 发放总量；普通券为 null（不限） */
    private Integer total;

    /** 当前剩余可领；普通券为 null（不限） */
    private Integer remain;

    /** 每人限领张数；★0 = 不限 */
    private Integer limitPerUser;

    /** 领后 N 天有效（券的 valid_type=DAYS 时） */
    private Integer couponValidDays;

    /** 券的可用范围：0 全场 / 1 限课程 / 2 限门店 */
    private Integer scope;
}

package com.yuedong.course.rule;

/**
 * 规则说明的场景码。
 *
 * <p>★为什么用枚举而不是字符串：新增一类课程/券时，编译器会在工厂里
 * 提醒"这个场景还没有实现类"（工厂找不到实现会退回默认文案并打 warn，
 * 但枚举给了一个可以对着数一遍的清单，漏没漏一眼看得出来）。</p>
 *
 * <p>只做<b>有调用方</b>的五个场景。文档 4.3.5 里还列了 {@code RESERVATION_CANCEL}
 * （预约页的取消规则），它的两句话已经进了 {@link RuleTextConstants}
 * 并被课程文案直接引用；再单独立一个没人调用的策略类，就是为假设的需求写代码。</p>
 */
public enum RuleScene {

    /** 按次售卖的可约课：要讲"几次、怎么用、过期怎么办" */
    COURSE_TIMES,

    /** 有效期内不限次的可约课 */
    COURSE_UNLIMITED,

    /** 展示型课程：免费跟练，无购买无预约 */
    COURSE_CONTENT,

    /** 秒杀券：限量、限每人领几张 */
    COUPON_SECKILL,

    /** 普通券：不限量，默认不限领 */
    COUPON_NORMAL
}

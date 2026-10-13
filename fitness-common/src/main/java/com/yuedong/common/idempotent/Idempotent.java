package com.yuedong.common.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口防重复提交（幂等）注解。
 *
 * <p>解决的问题：用户双击按钮、前端重试、网络卡顿后的重复点击，会让同一个动作被执行两次。
 * 本项目里典型的是"领券点两下领两张""评价提交两次""加购连点"。</p>
 *
 * <p>实现方式（见 {@link IdempotentAspect}）：以 <b>业务前缀 + 当前用户 + 请求路径</b> 组 key，
 * 用 Redis 的 {@code SETNX}（set if not exist，命令本身原子）在窗口期内占位；
 * 占不到就说明这个用户在窗口期内已经提交过一次，直接拒绝。</p>
 *
 * <p>用在哪、不用在哪（这是关键，别乱加）：</p>
 * <ul>
 *   <li>✅ 该加：领券、评价、加购物车、创建预约、秒杀下单——都是"重复执行会产生脏数据"的写接口；</li>
 *   <li>❌ 不该加：普通购买下单。<b>用户真心想再买一单</b>是正常行为，加了防重反而是 bug；
 *       这类接口该靠"同人同课去重 + 唯一索引"这类业务约束，而不是时间窗口；</li>
 *   <li>❌ 不该加：查询接口、匿名埋点上报（埋点靠事件自带的 eventId 做幂等，不是靠时间窗口）。</li>
 * </ul>
 *
 * <p>注意它和"业务唯一约束"是互补关系，不是替代关系：本注解拦的是<b>短时间内的重复提交</b>，
 * 拦不住隔了 3 秒的第二次并发请求，真正的最终防线仍然是数据库唯一索引。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * 业务前缀，用于区分同一个接口上的不同动作。
     *
     * <p>留空时切面用方法名当前缀（如 {@code couponReceive}），一般够用；
     * 只有在"同一个 URL 承担多种语义"时才需要显式指定，避免不同动作互相误拦。</p>
     */
    String prefix() default "";

    /**
     * 防重窗口，单位秒。默认 5 秒。
     *
     * <p>取值依据：人在 5 秒内对同一个按钮的重复点击几乎都想做同一件事；
     * 窗口再长就容易拦住"改完参数再提交一次"这种合理行为。</p>
     */
    int expireSecond() default 5;

    /**
     * 被拦下时给用户的提示文案。留空则用 {@code ErrorCode.REPEAT_SUBMIT} 的默认文案。
     */
    String message() default "";
}

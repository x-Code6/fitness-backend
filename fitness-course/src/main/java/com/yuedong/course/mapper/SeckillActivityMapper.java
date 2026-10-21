package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.SeckillActivity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀活动表 Mapper。
 */
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /**
     * 扣减 DB 剩余库存（秒杀消费端建单成功后调用，让 DB 与 Redis 最终一致）。
     */
    @Update("UPDATE t_seckill_activity SET remain = remain - 1 " +
            "WHERE id = #{activityId} AND remain > 0")
    int deductRemain(@Param("activityId") Long activityId);

    /**
     * 回补 DB 剩余库存（订单取消/超时/建单失败时调用）。
     *
     * <p>★为什么要有 {@code remain < total}：重复回补时不能再把库存加回去。
     * 调用方只在订单状态 CAS 成功后调一次（取消/超时），正常情况下不会重复；
     * 这个条件是一层额外的保险，防止人工重放把名额越补越多。</p>
     *
     * @return 1 回补成功；0 活动不存在或库存已经等于总量（无需回补）
     */
    @Update("UPDATE t_seckill_activity SET remain = remain + 1 " +
            "WHERE id = #{activityId} AND remain < total")
    int addRemain(@Param("activityId") Long activityId);

    /**
     * 统计与"待开始 / 进行中"活动时间重叠的场次。
     *
     * <p>区间重叠标准写法：{@code 已有.start < 新.end AND 已有.end > 新.start}。
     * 相邻（上一场 end == 下一场 start）不算重叠。</p>
     *
     * <p>★为什么创建时就要拦：同一门课同时有两场进行中的秒杀，
     * {@code activeSeckill} 的 {@code selectOne} 会拿到多条直接抛异常，
     * 用户看到的是下单 500，而不是"活动配置有问题"。</p>
     */
    @Select("SELECT COUNT(*) FROM t_seckill_activity " +
            "WHERE course_id = #{courseId} AND status IN (0, 1) " +
            "  AND start_time < #{endTime} AND end_time > #{startTime}")
    int countOverlapping(@Param("courseId") Long courseId,
                         @Param("startTime") java.time.LocalDateTime startTime,
                         @Param("endTime") java.time.LocalDateTime endTime);

    /**
     * 发放（待开始 → 进行中），CAS 版本。
     *
     * <p>★为什么必须带 {@code AND status = 0}：{@code @Scheduled} 在多实例部署下每台机器都会跑，
     * 单实例里也可能上一轮还没跑完下一轮又起来。用 {@code updateById} 会把状态无脑写成 1，
     * 于是"发放"这个动作被执行 N 次；带上 status 条件后，第二个执行者影响行数为 0，
     * 天然知道自己该跳过——<b>不需要引入 ShedLock 或分布式锁</b>。
     * 这和项目里 {@code booked < capacity}、{@code WHERE status='UNPAID'} 是同一个套路。</p>
     *
     * <p>同时写入 {@code preheat_at}，让"这个活动是什么时候发放的"在库里留痕，
     * 排查重复发放或漏发放时不必去翻日志。</p>
     *
     * @param activityId 活动 id
     * @param now        发放时间（由调用方传，便于单测里替换时间）
     * @return 1 本次执行完成了发放；0 已被别的实例/别轮次发放过，调用方应跳过
     */
    @Update("UPDATE t_seckill_activity SET status = 1, preheat_at = #{now} " +
            "WHERE id = #{activityId} AND status = 0")
    int markRunning(@Param("activityId") Long activityId, @Param("now") java.time.LocalDateTime now);

    /**
     * 结束（进行中 → 已结束），同样是 CAS。
     *
     * @return 1 本次执行完成了结束流转；0 已被处理过
     */
    @Update("UPDATE t_seckill_activity SET status = 2 " +
            "WHERE id = #{activityId} AND status = 1")
    int markEnded(@Param("activityId") Long activityId);
}

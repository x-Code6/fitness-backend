package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.SeckillActivity;
import org.apache.ibatis.annotations.Param;
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

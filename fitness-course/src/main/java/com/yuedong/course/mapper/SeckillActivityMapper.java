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
}

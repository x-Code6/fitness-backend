package com.yuedong.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.order.model.entity.StoreRatingStat;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 门店评分汇总 Mapper。
 *
 * <p>★<b>原子增减用 {@code INSERT ... ON DUPLICATE KEY UPDATE} 一条语句完成</b>，
 * 而不是"selectById → 内存里加 → updateById"。后者在读与写之间有时间窗，
 * 两个人同时给这家店打分就会互相覆盖，最后少算一次——
 * 这是全项目唯一一处"统计列"，也是它最容易被写错的地方。</p>
 *
 * <p>三张统计表分成三个 Mapper 而不是一个带 {@code ${table}} 参数的通用 Mapper：
 * 表名走参数拼接等于自己造 SQL 注入口子，
 * 而这里是<b>固定的三张表</b>，写三份没有损失。</p>
 */
public interface StoreRatingStatMapper extends BaseMapper<StoreRatingStat> {

    /**
     * 累加（deltaCount 传 -1 即回退）。
     *
     * @param targetId   门店 id
     * @param deltaSum   分数变化：新增评价 +score，删除评价 -score
     * @param deltaCount 条数变化：+1 或 -1
     */
    @Insert("INSERT INTO t_store_rating_stat (store_id, rating_sum, rating_count) " +
            "VALUES (#{targetId}, #{deltaSum}, #{deltaCount}) " +
            "ON DUPLICATE KEY UPDATE rating_sum = rating_sum + #{deltaSum}, " +
            "                        rating_count = GREATEST(rating_count + #{deltaCount}, 0)")
    int addScore(@Param("targetId") Long targetId,
                 @Param("deltaSum") Integer deltaSum,
                 @Param("deltaCount") Integer deltaCount);
}

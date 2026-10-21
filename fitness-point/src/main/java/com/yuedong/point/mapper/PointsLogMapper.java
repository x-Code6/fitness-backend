package com.yuedong.point.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.point.model.entity.PointsLog;
import com.yuedong.point.model.vo.RankScoreRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 积分流水 Mapper。 */
public interface PointsLogMapper extends BaseMapper<PointsLog> {

    /**
     * 某个月“每个人获得了多少积分”，按分数降序取前 N 名。
     *
     * <p>★归档用它而不是直接读 Redis ZSet：ZSet 是实时榜的缓存，
     * Redis 被清、ZINCRBY 失败都会让归档永久少分；流水是账本，可以重算。</p>
     *
     * <p>★只累加正数：兑换扣减是负数，但月榜比的是“本月赚了多少分”，
     * 兑换不应该把排名往下拉（实时榜 ZINCRBY 也只在打卡/完课时加）。</p>
     *
     * <p>★同分按 user_id 升序，保证归档名次稳定、重跑结果一致。</p>
     */
    @Select("SELECT user_id AS userId, " +
            "       SUM(CASE WHEN points > 0 THEN points ELSE 0 END) AS score " +
            "  FROM t_points_log " +
            " WHERE created_at >= #{startTime} AND created_at < #{endTime} " +
            " GROUP BY user_id " +
            " HAVING SUM(CASE WHEN points > 0 THEN points ELSE 0 END) > 0 " +
            " ORDER BY score DESC, user_id ASC " +
            " LIMIT #{limit}")
    List<RankScoreRow> selectMonthlyPositiveScores(@Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime,
                                                   @Param("limit") int limit);
}

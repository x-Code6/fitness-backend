package com.yuedong.point.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.point.model.entity.MemberPoints;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 会员积分汇总 Mapper。
 */
public interface MemberPointsMapper extends BaseMapper<MemberPoints> {

    /**
     * 原子加积分：余额与累计打卡一起加（避免"读-改-写"并发丢失），
     * 连续打卡天数由 Bitmap 反推后一并写入。
     */
    @Update("UPDATE t_member_points SET balance = balance + #{points}, " +
            "total_checkin = total_checkin + 1, continuous_days = #{continuousDays}, updated_at = NOW() " +
            "WHERE user_id = #{userId}")
    int addCheckinPoints(@Param("userId") Long userId,
                         @Param("points") Integer points,
                         @Param("continuousDays") Integer continuousDays);

    /**
     * 事件类加积分（完课/发动态）：只加余额，不动打卡统计。
     */
    @Update("UPDATE t_member_points SET balance = balance + #{points}, updated_at = NOW() " +
            "WHERE user_id = #{userId}")
    int addEventPoints(@Param("userId") Long userId, @Param("points") Integer points);

    /** 只更新连续打卡天数 */
    @Update("UPDATE t_member_points SET continuous_days = #{days}, updated_at = NOW() " +
            "WHERE user_id = #{userId}")
    int updateContinuous(@Param("userId") Long userId, @Param("days") Integer days);

    /**
     * 积分商城兑换：CAS 扣积分（余额不足时影响行数为 0），与库存扣减同事务。
     */
    @Update("UPDATE t_member_points SET balance = balance - #{cost}, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND balance >= #{cost}")
    int deductForExchange(@Param("userId") Long userId, @Param("cost") Integer cost);
}

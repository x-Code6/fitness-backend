package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.Coupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 优惠券 Mapper：剩余可领数原子扣减。 */
public interface CouponMapper extends BaseMapper<Coupon> {

    @Update("UPDATE t_coupon SET remain = remain - 1 " +
            "WHERE id = #{couponId} AND remain > 0")
    int deductRemain(@Param("couponId") Long couponId);

    /**
     * 锁住券模板行，供普通券"判限领 + 插入持券记录"使用。
     * <p>★普通券没有库存行可扣，限领是 {@code COUNT(*)}；两个并发请求会同时
     * COUNT 到 limit-1 然后都插入。锁定同一张券的模板行，让这两个请求串行，
     * 第二次 COUNT 就能看到第一次插入的记录。低频动作，行锁成本可接受。</p>
     */
    @Select("SELECT * FROM t_coupon WHERE id = #{couponId} FOR UPDATE")
    Coupon selectByIdForUpdate(@Param("couponId") Long couponId);
}

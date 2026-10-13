package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.Coupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 优惠券 Mapper：剩余可领数原子扣减。 */
public interface CouponMapper extends BaseMapper<Coupon> {

    @Update("UPDATE t_coupon SET remain = remain - 1 " +
            "WHERE id = #{couponId} AND remain > 0")
    int deductRemain(@Param("couponId") Long couponId);
}

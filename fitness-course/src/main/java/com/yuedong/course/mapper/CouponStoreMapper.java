package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.CouponStore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/** 券-可用门店 Mapper（与 {@link CouponCourseMapper} 同一套"整组覆盖"写法） */
public interface CouponStoreMapper extends BaseMapper<CouponStore> {

    @Delete("DELETE FROM t_coupon_store WHERE coupon_id = #{couponId}")
    int deleteByCoupon(@Param("couponId") Long couponId);
}

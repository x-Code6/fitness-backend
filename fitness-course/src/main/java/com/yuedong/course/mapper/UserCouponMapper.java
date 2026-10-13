package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.UserCoupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 用户持券 Mapper：用券/退券均为 CAS 状态流转。 */
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /** 用券：仅 UNUSED 可核销（绑定订单号） */
    @Update("UPDATE t_user_coupon SET status = 'USED', used_at = NOW(), order_no = #{orderNo} " +
            "WHERE id = #{recordId} AND user_id = #{userId} AND status = 'UNUSED'")
    int use(@Param("recordId") Long recordId, @Param("userId") Long userId,
            @Param("orderNo") String orderNo);

    /** 退券：仅 USED 可回退（订单取消/超时） */
    @Update("UPDATE t_user_coupon SET status = 'UNUSED', used_at = NULL, order_no = NULL " +
            "WHERE id = #{recordId} AND status = 'USED'")
    int refund(@Param("recordId") Long recordId);
}

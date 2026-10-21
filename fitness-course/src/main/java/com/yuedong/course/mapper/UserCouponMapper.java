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

    /**
     * 退券：仅"这张券确实用在这张订单上"才回退。
     *
     * <p>★必须带 {@code order_no} 条件：同一张券被订单 A 退款后，可以再次用于订单 B；
     * 如果订单 A 的延迟退款/人工重试只按 recordId 退，就会把订单 B 正在使用的券
     * 也退回成 UNUSED，用户就能再用一次。</p>
     */
    @Update("UPDATE t_user_coupon SET status = 'UNUSED', used_at = NULL, order_no = NULL " +
            "WHERE id = #{recordId} AND order_no = #{orderNo} AND status = 'USED'")
    int refund(@Param("recordId") Long recordId, @Param("orderNo") String orderNo);
}

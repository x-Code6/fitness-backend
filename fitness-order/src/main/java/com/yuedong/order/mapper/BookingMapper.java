package com.yuedong.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.order.model.entity.Booking;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 预约订单 Mapper。
 *
 * <p>状态流转全部是"带当前状态的 CAS 式 UPDATE"：返回 1 表示流转成功，
 * 返回 0 说明状态已被并发请求改变（重复支付/已取消），调用方提示刷新即可。</p>
 */
public interface BookingMapper extends BaseMapper<Booking> {

    /**
     * 支付：仅 UNPAID → BOOKED（模拟支付；真实项目此处替换为支付回调驱动）。
     */
    @Update("UPDATE t_booking SET status = 'BOOKED', pay_time = NOW() " +
            "WHERE order_no = #{orderNo} AND user_id = #{userId} AND status = 'UNPAID'")
    int markPaid(@Param("orderNo") String orderNo, @Param("userId") Long userId);

    /**
     * 取消：UNPAID/BOOKED → CANCELED（用户主动取消与超时自动取消共用）。
     */
    @Update("UPDATE t_booking SET status = 'CANCELED', cancel_reason = #{reason} " +
            "WHERE order_no = #{orderNo} AND status IN ('UNPAID', 'BOOKED')")
    int cancel(@Param("orderNo") String orderNo, @Param("reason") String reason);

    /**
     * 核销：仅 BOOKED → FINISHED（一码一次，CAS 保证重复核销影响行数为 0）。
     */
    @Update("UPDATE t_booking SET status = 'FINISHED', verified_at = NOW() " +
            "WHERE verify_code = #{verifyCode} AND status = 'BOOKED'")
    int markVerified(@Param("verifyCode") String verifyCode);
}

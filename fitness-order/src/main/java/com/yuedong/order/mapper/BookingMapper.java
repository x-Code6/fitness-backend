package com.yuedong.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.order.model.entity.Booking;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 订单表 Mapper。
 *
 * <p>本接口的重点：所有状态流转都是<b>带状态条件的原子 UPDATE（CAS）</b>，
 * 靠"影响行数是否为 1"判断成败，而不是"先查出来判断再 update"。
 * 后者在读-改-写之间有时间窗，并发下会出现两个请求都认为自己可以流转。</p>
 */
public interface BookingMapper extends BaseMapper<Booking> {

    /**
     * 支付：UNPAID → PAID。
     *
     * <p>条件里带 {@code status = 'UNPAID'} 有两个作用：① 重复支付只有第一次生效；
     * ② <b>已取消或已退款的单不可能被支付</b>——这也是 15 分钟超时取消能安全工作的前提
     * （两边抢同一行，谁先改成功谁说了算）。</p>
     *
     * @return 1 支付成功；0 状态不允许（已支付/已取消/不存在）
     */
    @Update("UPDATE t_booking SET status = 'PAID', pay_time = #{now} " +
            "WHERE order_no = #{orderNo} AND user_id = #{userId} AND status = 'UNPAID'")
    int markPaid(@Param("orderNo") String orderNo,
                 @Param("userId") Long userId,
                 @Param("now") LocalDateTime now);

    /**
     * 整组支付：购物车一次结算出的多张订单一起流转，返回被更新的实际行数。
     *
     * <p>★用一条 UPDATE 而不是循环调 {@link #markPaid}：循环里第 3 张失败时前 2 张已经提交，
     * 用户会遇到"付了一部分"。整组一条语句 + 外层事务，才是"一次支付一组订单"的正确形态。</p>
     */
    @Update("UPDATE t_booking SET status = 'PAID', pay_time = #{now} " +
            "WHERE parent_no = #{parentNo} AND user_id = #{userId} AND status = 'UNPAID'")
    int markGroupPaid(@Param("parentNo") String parentNo,
                      @Param("userId") Long userId,
                      @Param("now") LocalDateTime now);

    /**
     * 取消：只有未支付的单能取消（已支付走退款，不是取消）。
     *
     * @return 1 取消成功；0 状态已是 PAID/CANCELED，用户该刷新页面
     */
    @Update("UPDATE t_booking SET status = 'CANCELED', cancel_reason = #{reason}, canceled_at = #{now} " +
            "WHERE order_no = #{orderNo} AND user_id = #{userId} AND status = 'UNPAID'")
    int cancel(@Param("orderNo") String orderNo,
               @Param("userId") Long userId,
               @Param("reason") String reason,
               @Param("now") LocalDateTime now);

    /**
     * 整组取消：购物车合并下单出的 N 张待支付单一起取消。
     *
     * <p>★和 {@link #markGroupPaid} 对称：一组单要么整体付掉、要么整体取消，
     * 让用户在收银台上看到"3 张里取消了 2 张"是最糟糕的状态。</p>
     */
    @Update("UPDATE t_booking SET status = 'CANCELED', cancel_reason = #{reason}, canceled_at = #{now} " +
            "WHERE parent_no = #{parentNo} AND user_id = #{userId} AND status = 'UNPAID'")
    int cancelGroup(@Param("parentNo") String parentNo,
                    @Param("userId") Long userId,
                    @Param("reason") String reason,
                    @Param("now") LocalDateTime now);

    /**
     * 超时未支付自动取消：与 {@link #cancel} 的区别是<b>不校验 user_id</b>（MQ 消费场景）。
     *
     * <p>★条件仍只有 {@code status='UNPAID'}，所以"用户已经支付完了，延迟消息才到"这种情况
     * 影响行数为 0，什么都不做。这就是 RabbitMQ 延迟消息不需要"撤回"的实现方式：
     * <b>消费时用状态判等，比撤回消息简单且不会出错</b>。</p>
     */
    @Update("UPDATE t_booking SET status = 'CANCELED', cancel_reason = '超时未支付，自动取消', " +
            "canceled_at = #{now} WHERE order_no = #{orderNo} AND status = 'UNPAID'")
    int cancelIfUnpaid(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    /**
     * 退款：PAID → REFUNDED，由"凭证过期且一次都没用"的定时任务触发。
     *
     * <p>★这里只改状态、不动钱：本项目不接支付网关（明确的产品决定），
     * 所以"已退款"是一句状态 + 前端展示，不产生任何资金流。</p>
     */
    @Update("UPDATE t_booking SET status = 'REFUNDED', refunded = 1, refund_at = #{now} " +
            "WHERE order_no = #{orderNo} AND status = 'PAID'")
    int markRefunded(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);
}

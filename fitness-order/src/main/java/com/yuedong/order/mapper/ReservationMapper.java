package com.yuedong.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.order.model.entity.Reservation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 预约单 Mapper。
 *
 * <p>★三个方法都是<b>带状态条件的原子 UPDATE（CAS）</b>，判断成败只看影响行数，
 * 不做"先查再改"。这一条在预约上尤其重要：用户点取消的同时定时任务正在判定到点，
 * 两个写者必须只有一个能改到状态，否则会出现"取消了又被扣一次"。</p>
 */
public interface ReservationMapper extends BaseMapper<Reservation> {

    /**
     * 用户取消：只有"还在 BOOKED 且没到取消截止"的单能取消。
     *
     * <p>★{@code cancel_deadline > #{now}} 把"提前 1 小时才能取消"这条规则
     * <b>固化在数据列上而不是代码里</b>：Java 不做任何时间计算，
     * 也不需要应用服务器与数据库时钟严格一致（比较发生在数据库内部）。</p>
     *
     * <p>影响 0 行有三种可能：单不是自己的、状态已是 FINISHED/CANCELED、
     * 距开课不足 1 小时。三者给同一句提示（docs 流程 D），因为对用户来说
     * 区别没有意义——都只能找门店处理。</p>
     *
     * @return 1 取消成功；0 不允许取消
     */
    @Update("UPDATE t_reservation SET status = 'CANCELED' " +
            "WHERE reservation_no = #{reservationNo} AND user_id = #{userId} " +
            "  AND status = 'BOOKED' AND cancel_deadline > #{now}")
    int cancel(@Param("reservationNo") String reservationNo,
               @Param("userId") Long userId,
               @Param("now") LocalDateTime now);

    /**
     * 到点结账：BOOKED → FINISHED（已确认口径：按 {@code end_time} 判定，下课才算消费完）。
     *
     * <p>★为什么不用"先查一批再逐条 update"里的查询条件当准：查询到更新的间隙里
     * 用户可能刚好取消成功，所以 {@code AND status='BOOKED'} 才是最终防线，
     * 影响 0 行就跳过。这就是定时任务不需要分布式锁的原因。</p>
     *
     * @return 1 本次结账成功（调用方据此决定是否扣次）；0 已被处理
     */
    @Update("UPDATE t_reservation SET status = 'FINISHED', finished_at = #{now} " +
            "WHERE id = #{id} AND status = 'BOOKED' AND end_time <= #{now}")
    int markFinished(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 到店核销：<b>只登记到店</b>，不改状态、不扣次（D5 把核销从业务闸门降级成记录）。
     *
     * <p>{@code verified_at IS NULL} 保证一码一次：重复扫同一个码影响 0 行，
     * 前端提示"该码已核销"。</p>
     */
    @Update("UPDATE t_reservation SET verified_at = #{now} " +
            "WHERE verify_code = #{verifyCode} AND status = 'BOOKED' AND verified_at IS NULL")
    int markVerified(@Param("verifyCode") String verifyCode, @Param("now") LocalDateTime now);
}

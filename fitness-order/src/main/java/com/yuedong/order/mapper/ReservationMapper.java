package com.yuedong.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.order.model.entity.Reservation;
import com.yuedong.order.model.vo.ReservationCountRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 某一天被占用的<b>格子数</b>（看板"时段占用率"的分子）。
     *
     * <p>★累加的是 {@code lesson_hours} 而不是行数：一节 2 小时的课占 2 个整点格子，
     * 按行数算会把占用率低算一半，而分母（教练工时）是按小时算的，
     * <b>分子分母必须同一单位</b>——这条和贝叶斯评分里"分子分母口径要一致"是同一类错误。</p>
     *
     * <p>已取消的单不算：它占的格子在取消那一刻就被释放了（删了 t_coach_busy 行），
     * 再算进分子就等于把已经还给别人的时段又宣称占着。</p>
     */
    @Select("SELECT COALESCE(SUM(lesson_hours), 0) FROM t_reservation" +
            " WHERE status <> 'CANCELED' AND start_time >= #{start} AND start_time < #{end}")
    Integer sumSlotsBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 整页凭证各被约过几次（管理端凭证列表的"已约 N 次"）。
     * <p>★一条 GROUP BY 拿完整页；已取消的不算——用户取消后那次占用还给了凭证，
     * 记进"已约次数"会让运营以为这张凭证比实际用得少。</p>
     */
    @Select("<script>" +
            "SELECT entitlement_id, COUNT(*) AS reservation_count FROM t_reservation" +
            " WHERE status &lt;&gt; 'CANCELED' AND entitlement_id IN " +
            "<foreach collection='entitlementIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            " GROUP BY entitlement_id" +
            "</script>")
    List<ReservationCountRow> countByEntitlements(@Param("entitlementIds") List<Long> entitlementIds);
}

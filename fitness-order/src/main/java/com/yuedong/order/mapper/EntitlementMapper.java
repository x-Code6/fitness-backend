package com.yuedong.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.order.model.entity.Entitlement;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 凭证表 Mapper。
 *
 * <p>和 {@link BookingMapper} 一样，凡是改状态的语句都是<b>带状态条件的原子 UPDATE（CAS）</b>：
 * 靠影响行数判断成败，不做"先查出来判断再 update"。凭证涉及<b>次数</b>，
 * 属于并发防御三档里的"必须防"（docs 4.8）。</p>
 */
public interface EntitlementMapper extends BaseMapper<Entitlement> {

    /**
     * 扣一次（预约到点、上完课时调用）。
     *
     * <p>★<b>SET 里两个赋值是有意排的序</b>：MySQL 对 {@code SET a=..., b=...} 是
     * 从左到右求值，后一个表达式读到的是<b>前一个赋值之后的新值</b>。
     * 所以这里必须把 status 的判断写在 {@code used_times = used_times + 1} <b>之前</b>，
     * 让它读到"扣次前"的旧值，条件才能写成 {@code used_times + 1 >= total_times}。
     * 反过来写会变成"新值 + 1"，第二次扣次就提前把凭证判成 USED_UP。</p>
     *
     * <p>WHERE 里三个条件各自拦一种并发：</p>
     * <ul>
     *   <li>{@code status='ACTIVE'} —— 已用完/已过期的凭证不能再被扣，定时任务重跑安全；</li>
     *   <li>{@code total_times IS NULL OR used_times < total_times} —— 不限次课（NULL）放行，
     *       按次课用完后再扣就是 0 行。★漏掉前半句会把 UNLIMITED 当 0 次卡；</li>
     * </ul>
     *
     * @return 1 扣次成功；0 这张凭证已不可用（重复扣次不会多加次数）
     */
    @Update("UPDATE t_entitlement " +
            "SET status = CASE WHEN total_times IS NOT NULL AND used_times + 1 >= total_times " +
            "                   THEN 'USED_UP' ELSE status END, " +
            "    used_times = used_times + 1 " +
            "WHERE id = #{id} AND status = 'ACTIVE' " +
            "  AND (total_times IS NULL OR used_times < total_times)")
    int consumeOne(@Param("id") Long id);

    /**
     * 过期（由每小时扫描任务调用）。
     *
     * <p>★条件带 {@code valid_until IS NOT NULL}：FOREVER 形态的凭证 valid_until 是 null，
     * 少了这个判断，一句 {@code valid_until < NOW()} 在 SQL 里恒为 NULL（不是真），
     * 反倒不会误伤 —— 但扫描任务的 SELECT 必须显式写 IS NOT NULL，
     * 别把正确性建立在三值逻辑的巧合上。</p>
     *
     * @return 1 置为过期；0 已被处理过（CAS，重跑安全）
     */
    @Update("UPDATE t_entitlement SET status = 'EXPIRED' " +
            "WHERE id = #{id} AND status = 'ACTIVE' AND valid_until IS NOT NULL AND valid_until < #{now}")
    int expireOne(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 标记这张凭证已退款。条件是"已过期、一次都没用过、还没退过"。
     *
     * <p>★为什么要 status='EXPIRED'：退款只发生在过期任务里，先由 {@link #expireOne}
     * 把状态推进到 EXPIRED，再在同一事务里退钱。顺序反过来会出现
     * "钱退了，凭证还是 ACTIVE，用户还能继续约"。</p>
     *
     * <p>★refunded_at IS NULL 是幂等条件：重复扫描/多实例同时跑时只有第一次能退。</p>
     *
     * @return 1 本次完成退款标记；0 已退过或条件不满足
     */
    @Update("UPDATE t_entitlement SET refund_amount = COALESCE(paid_amount, 0), refunded_at = #{now} " +
            "WHERE id = #{id} AND status = 'EXPIRED' AND used_times = 0 AND refunded_at IS NULL")
    int markRefunded(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 查"已过期、且当前没有待上预约"的凭证，供过期任务处理。
     *
     * <p>★{@code NOT EXISTS} 这一段不能省：如果凭证到期时还有一节 BOOKED 的课
     * （例如 2 小时的课从到期前 1 小时开始），直接置 EXPIRED 并退款，等下课结算时
     * {@code consumeOne} 会扣不动，用户既上了课又拿了退款。</p>
     *
     * <p>有 BOOKED 的凭证先跳过，等预约结算（下课扣次）或用户取消后再处理；
     * 下一轮任务会重新捞到它。</p>
     */
    @Select("SELECT e.* FROM t_entitlement e " +
            "WHERE e.status = 'ACTIVE' AND e.valid_until IS NOT NULL AND e.valid_until < #{now} " +
            "  AND NOT EXISTS (SELECT 1 FROM t_reservation r " +
            "                  WHERE r.entitlement_id = e.id AND r.status = 'BOOKED') " +
            "ORDER BY e.valid_until ASC LIMIT #{limit}")
    List<Entitlement> selectExpiredWithoutPending(@Param("now") LocalDateTime now,
                                                  @Param("limit") int limit);
}

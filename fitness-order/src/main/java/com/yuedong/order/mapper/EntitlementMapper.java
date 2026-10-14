package com.yuedong.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.order.model.entity.Entitlement;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

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
}

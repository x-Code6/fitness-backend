package com.yuedong.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.order.model.entity.CoachBusy;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 教练时段占用 Mapper。
 *
 * <p>本表的操作只有三种，全部极简：<b>插（占格）</b>、<b>删（取消释放）</b>、
 * <b>查（算空闲）</b>。没有"改"——占用是事实记录，不是可编辑状态。</p>
 *
 * <p>★并发正确性全部由唯一索引 {@code uk_coach_start} 提供：插入撞键就抛
 * {@code DuplicateKeyException}，外层事务回滚，本类不需要任何 FOR UPDATE 或锁。</p>
 */
public interface CoachBusyMapper extends BaseMapper<CoachBusy> {

    /**
     * 取消预约时释放它占的全部格子。
     *
     * <p>★用 {@code reservation_id} 一次删完而不是按 (coach_id, start_time) 逐行删：
     * 前者命中 {@code idx_reservation} 且天然只删得掉自己那一单的行；
     * 后者要先把时间再算一遍，算错一格就留下一个"幽灵占用"，
     * 那个格子会永远显示被占、谁也约不了，而且极难排查。</p>
     *
     * @return 删除行数（= 这节课占几个整点就是几行）
     */
    @Delete("DELETE FROM t_coach_busy WHERE reservation_id = #{reservationId}")
    int deleteByReservation(@Param("reservationId") Long reservationId);
}

package com.yuedong.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教练时段占用实体，对应表 yd_order.t_coach_busy —— <b>v2 并发防护的核心</b>。
 *
 * <p>一行 = 一个小时格子。{@code lessonHours=2} 的课要插两行（18:00 与 19:00）。</p>
 *
 * <p>★为什么这张表放在 yd_order 而不是 yd_course：创建预约要在<b>同一个本地事务</b>里
 * 写"预约单 + 占用格子"，靠事务回滚代替手写补偿；跨库就做不到，只能退回旧模型那种
 * Feign 占位 + 三处补偿的写法（那是 bug 的温床）。</p>
 *
 * <p>★为什么这一行值得存在：唯一索引 {@code uk_coach_start(coach_id, start_time)}
 * 把"同一教练同一时段不能被两人约"从<b>区间重叠判断</b>（要 SELECT FOR UPDATE、
 * 锁等待、死锁处理）降级成<b>格子存在性判断</b>：两人抢同一格，后插入的人必然撞唯一键，
 * 事务自动回滚，不需要任何锁、Lua 或分布式协调。</p>
 *
 * <p>生命周期：<b>只有取消才删</b>。上完课（FINISHED）的行要留着——那段时间真被占了，
 * 删掉就等于把已经用掉的时段又放出去，会被双倍预约。</p>
 */
@Data
@TableName("t_coach_busy")
public class CoachBusy {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 哪位教练（跨库 → yd_course.t_coach） */
    @TableField("coach_id")
    private Long coachId;

    /** 所在门店（便于按店统计占用率，也用于"教练不能跨店约"的事后核对） */
    @TableField("store_id")
    private Long storeId;

    /** ★整点格子起点，一行占一个小时 */
    @TableField("start_time")
    private LocalDateTime startTime;

    /** 属于哪张预约单，取消时按它一次删掉该单占的全部格子 */
    @TableField("reservation_id")
    private Long reservationId;

    /** 谁占的。★时段页靠它区分两种灰态：别人占的(BOOKED)与自己已约的(SELF_USED) */
    @TableField("user_id")
    private Long userId;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

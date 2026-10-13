package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程场次实体（PO），对应表 yd_course.t_course_schedule。
 *
 * <p>引入场次的意义：同一门课有多个上课时间，用户预约的是"具体某一场"，
 * 名额按场次隔离占用（每场各自的 capacity/booked），而不是笼统扣课程容量。</p>
 */
@Data
@TableName("t_course_schedule")
public class Schedule {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属课程 */
    @TableField("course_id")
    private Long courseId;

    /** 上课时间 */
    @TableField("schedule_time")
    private LocalDateTime scheduleTime;

    /** 本场容量 */
    @TableField("capacity")
    private Integer capacity;

    /** 本场已约 */
    @TableField("booked")
    private Integer booked;

    /** 1 可约 / 0 已取消 */
    @TableField("status")
    private Integer status;
}

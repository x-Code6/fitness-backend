package com.yuedong.course.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程场次视图对象（课程详情页的"选择上课时间"列表项）。
 */
@Data
@Builder
public class ScheduleVO {

    private Long id;

    /** 上课时间 */
    private LocalDateTime scheduleTime;

    private Integer capacity;

    private Integer booked;

    /** 本场是否已满（前端置灰不可选） */
    private boolean full;
}

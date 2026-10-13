package com.yuedong.order.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 普通预约请求参数。
 */
@Data
public class BookingCreateDTO {

    /** 课程 id */
    @NotNull(message = "课程 id 不能为空")
    private Long courseId;

    /** 场次 id（预约具体某一场；必选——详情页需先选上课时间） */
    @NotNull(message = "请先选择上课时间")
    private Long scheduleId;

    /** 使用的持券记录 id（不用户券可不传） */
    private Long couponRecordId;
}

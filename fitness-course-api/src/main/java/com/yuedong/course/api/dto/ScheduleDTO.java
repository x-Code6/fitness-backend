package com.yuedong.course.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 场次 DTO（订单服务按场次预约 + 管理端排期列表共用）。
 */
@Data
@Builder
public class ScheduleDTO {

    private Long id;

    private Long courseId;

    /** 上课时间 */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime scheduleTime;

    private Integer capacity;

    private Integer booked;

    /** 1=正常 0=停用 */
    private Integer status;
}

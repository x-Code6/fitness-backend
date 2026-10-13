package com.yuedong.course.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 管理端秒杀活动行 DTO（含课程名，列表展示用）。
 */
@Data
@Builder
public class SeckillAdminDTO {

    private Long id;

    private Long courseId;

    private String courseName;

    private BigDecimal seckillPrice;

    private Integer total;

    private Integer remain;

    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")

    private LocalDateTime startTime;

    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")

    private LocalDateTime endTime;

    /** 0=待开始 1=进行中 2=已结束 */
    private Integer status;
}

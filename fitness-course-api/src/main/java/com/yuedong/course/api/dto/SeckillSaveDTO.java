package com.yuedong.course.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端创建秒杀活动请求参数。
 */
@Data
public class SeckillSaveDTO {

    @NotNull(message = "课程不能为空")
    private Long courseId;

    @NotNull(message = "秒杀价不能为空")
    private BigDecimal seckillPrice;

    @NotNull(message = "名额不能为空")
    private Integer total;

    /** 开始时间（yyyy-MM-dd HH:mm:ss） */
    @NotBlank(message = "开始时间不能为空")
    private String startTime;

    /** 结束时间 */
    @NotBlank(message = "结束时间不能为空")
    private String endTime;
}

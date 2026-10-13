package com.yuedong.course.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端创建/编辑课程请求参数。
 */
@Data
public class CourseSaveDTO {

    /** 编辑时传 id */
    private Long id;

    @NotBlank(message = "课程名称不能为空")
    private String name;

    @NotBlank(message = "类型不能为空")
    private String type;

    @NotBlank(message = "教练不能为空")
    private String coach;

    private String coachTitle;

    @NotNull(message = "时长不能为空")
    private Integer duration;

    @NotNull(message = "消耗不能为空")
    private Integer calories;

    @NotNull(message = "容量不能为空")
    private Integer capacity;

    @NotNull(message = "价格不能为空")
    private BigDecimal price;

    /** 标签，逗号分隔 */
    private String tags;

    @NotBlank(message = "门店不能为空")
    private String storeName;

    private String nextTime;

    private String description;

    private String suitable;
}

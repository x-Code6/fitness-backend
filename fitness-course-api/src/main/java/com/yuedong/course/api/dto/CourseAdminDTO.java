package com.yuedong.course.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端课程分页行 DTO（列表展示 + 编辑弹窗回填所需字段）。
 */
@Data
@Builder
public class CourseAdminDTO {

    private Long id;

    private String name;

    /** 课程类型：团课/私教/瑜伽/骑行/操课 */
    private String type;

    private String coach;

    private String coachTitle;

    /** 时长（分钟） */
    private Integer duration;

    /** 消耗（千卡） */
    private Integer calories;

    private Integer capacity;

    private Integer booked;

    private BigDecimal price;

    /** 标签，逗号分隔 */
    private String tags;

    private String storeName;

    private String nextTime;

    private String description;

    private String suitable;

    /** 1=上架 0=下架 */
    private Integer status;
}

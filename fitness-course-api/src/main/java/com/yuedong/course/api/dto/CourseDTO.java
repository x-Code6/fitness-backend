package com.yuedong.course.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 课程简况 DTO（订单服务下单时 Feign 获取：展示快照 + 名额校验基准）。
 */
@Data
@Builder
public class CourseDTO {

    private Long id;

    private String name;

    private String coach;

    private String storeName;

    /** 原价 */
    private BigDecimal price;

    private String nextTime;

    private Integer capacity;

    private Integer booked;
}

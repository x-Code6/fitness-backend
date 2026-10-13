package com.yuedong.course.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 门店 DTO（管理端门店列表/编辑回填）。
 */
@Data
@Builder
public class StoreDTO {

    private Long id;

    private String name;

    /** 经度（Redis GEO 用） */
    private BigDecimal lng;

    /** 纬度（Redis GEO 用） */
    private BigDecimal lat;

    private String address;

    /** 评分（0-5） */
    private BigDecimal rating;

    /** 标签，逗号分隔 */
    private String tags;

    private String openHours;

    private String phone;

    /** 教练数 */
    private Integer coaches;
}

package com.yuedong.course.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 门店视图对象（附近门店列表）。
 */
@Data
@Builder
public class StoreVO {

    private Long id;

    private String name;

    /** 距用户位置的距离（公里，Redis GEO 计算） */
    private Double distance;

    private String address;

    private java.math.BigDecimal rating;

    private java.util.List<String> tags;

    private String openHours;

    private String phone;

    private Integer coaches;
}

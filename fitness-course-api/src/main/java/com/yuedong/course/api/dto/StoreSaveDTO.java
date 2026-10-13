package com.yuedong.course.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端新增/编辑门店请求参数。
 */
@Data
public class StoreSaveDTO {

    /** 编辑时传 id */
    private Long id;

    @NotBlank(message = "门店名不能为空")
    private String name;

    @NotNull(message = "经度不能为空")
    private BigDecimal lng;

    @NotNull(message = "纬度不能为空")
    private BigDecimal lat;

    @NotBlank(message = "地址不能为空")
    private String address;

    private BigDecimal rating;

    private String tags;

    private String openHours;

    private String phone;

    private Integer coaches;
}

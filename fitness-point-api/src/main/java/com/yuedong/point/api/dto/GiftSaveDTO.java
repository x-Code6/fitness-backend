package com.yuedong.point.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端新增/编辑积分商城商品请求参数。
 */
@Data
public class GiftSaveDTO {

    /** 编辑时传 id */
    private Long id;

    @NotBlank(message = "商品名不能为空")
    private String name;

    @NotNull(message = "所需积分不能为空")
    @Min(value = 1, message = "所需积分必须大于 0")
    private Integer pointsCost;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    /** 商品图访问路径（上传接口返回值），可空 */
    @Size(max = 255, message = "商品图路径过长")
    private String image;
}

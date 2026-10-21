package com.yuedong.point.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 积分商城商品 DTO（管理端商品分页行）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GiftDTO {

    private Long id;

    private String name;

    /** 兑换所需积分 */
    private Integer pointsCost;

    private Integer stock;

    /** 商品图访问路径；管理端编辑时回填 */
    private String image;

    /** 1=在售 0=下架 */
    private Integer status;
}

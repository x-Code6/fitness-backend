package com.yuedong.point.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 积分商城商品 DTO（管理端商品分页行）。
 */
@Data
@Builder
public class GiftDTO {

    private Long id;

    private String name;

    /** 兑换所需积分 */
    private Integer pointsCost;

    private Integer stock;

    /** 1=在售 0=下架 */
    private Integer status;
}

package com.yuedong.point.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 积分商城商品视图（用户端积分商城页）。
 */
@Data
@Builder
public class GiftVO {

    private Long id;

    private String name;

    /** 兑换所需积分 */
    private Integer pointsCost;

    private Integer stock;

    /** 商品图访问路径；为空时前端用积分图标兜底 */
    private String image;
}

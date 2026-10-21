package com.yuedong.point.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 积分商城商品实体（PO），对应表 yd_point.t_gift。
 */
@Data
@TableName("t_gift")
public class Gift {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 商品名 */
    @TableField("name")
    private String name;

    /** 兑换所需积分 */
    @TableField("points_cost")
    private Integer pointsCost;

    /** 库存 */
    @TableField("stock")
    private Integer stock;

    /** 商品图访问路径（/uploads/gift/xxx.png），空表示前端用图标兜底 */
    @TableField("image")
    private String image;

    /** 1 上架 / 0 下架 */
    @TableField("status")
    private Integer status;
}

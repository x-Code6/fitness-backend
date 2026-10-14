package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 券-可用门店（{@code scope=2} 时生效），对应表 yd_course.t_coupon_store。
 * <p>存在的理由与 {@link CouponCourse} 完全一样：范围判断要走索引，不能靠字符串 LIKE。</p>
 */
@Data
@TableName("t_coupon_store")
public class CouponStore {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("coupon_id")
    private Long couponId;

    @TableField("store_id")
    private Long storeId;
}

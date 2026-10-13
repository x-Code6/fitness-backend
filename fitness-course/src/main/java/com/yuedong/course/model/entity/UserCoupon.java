package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户持券记录实体（PO），对应表 yd_course.t_user_coupon。
 * UNIQUE(user_id, coupon_id)：一人一券。
 */
@Data
@TableName("t_user_coupon")
public class UserCoupon {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 持券用户 id */
    @TableField("user_id")
    private Long userId;

    /** 券 id */
    @TableField("coupon_id")
    private Long couponId;

    /** UNUSED / USED */
    @TableField("status")
    private String status;

    /** 核销订单号 */
    @TableField("order_no")
    private String orderNo;

    /** 领取时间 */
    @TableField("received_at")
    private LocalDateTime receivedAt;

    /** 使用时间（核销后写入） */
    @TableField("used_at")
    private LocalDateTime usedAt;
}

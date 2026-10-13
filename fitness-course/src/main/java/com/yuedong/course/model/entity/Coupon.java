package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券实体（PO），对应表 yd_course.t_coupon。
 *
 * <p>领券与秒杀同构：Redis 库存（coupon:stock:{id}）+ 一人一券集合
 * （coupon:got:{id}）Lua 原子扣减，DB.remain 最终一致 ——
 * 等于把秒杀框架沉淀成了通用"限量抢购"能力。</p>
 */
@Data
@TableName("t_coupon")
public class Coupon {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 券名（如"新人立减券"） */
    @TableField("title")
    private String title;

    /** 使用门槛：满 X 元可用（0 = 无门槛） */
    @TableField("threshold")
    private BigDecimal threshold;

    /** 抵扣金额 */
    @TableField("discount")
    private BigDecimal discount;

    /** 发放总量 */
    @TableField("total")
    private Integer total;

    /** DB 剩余可领 */
    @TableField("remain")
    private Integer remain;

    /** 每人限领（当前实现固定 1 张） */
    @TableField("limit_per_user")
    private Integer limitPerUser;

    /** 领取开始时间 */
    @TableField("start_time")
    private LocalDateTime startTime;

    /** 领取截止 = 使用截止 */
    @TableField("end_time")
    private LocalDateTime endTime;

    /** 1 上架 / 0 下架 / 2 作废 */
    @TableField("status")
    private Integer status;
}

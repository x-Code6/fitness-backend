package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板实体，对应表 yd_course.t_coupon。
 *
 * <p>★v2 的四处扩展，每处都对应一段独立逻辑，混在一起就会出错：</p>
 * <ol>
 *   <li><b>券分两类</b>（{@code couponType}）：SECKILL 限量 + 限每人领，走 Redis Lua；
 *       NORMAL 不限量、默认不限领，走普通 COUNT 判断。两类券的管理端表单和前端卡片都不一样；</li>
 *   <li><b>"能不能领"与"领到以后能不能用"彻底分成两组时间</b>。
 *       旧库只有一组 {@code start_time/end_time}，兼职表达"领取截止"，
 *       于是"领后 7 天有效"这种最常见的规则根本配不出来；</li>
 *   <li><b>每人限领 {@code limitPerUser} 张</b>（0 = 不限）。旧实现靠
 *       {@code uk_user_coupon(user_id,coupon_id)} 在数据库层面锁死"一人一张"，
 *       v2 已删掉那个唯一索引，限领改成计数判断；</li>
 *   <li><b>可用范围</b>（{@code scope}）：0 全场 / 1 限课程（t_coupon_course）/ 2 限门店（t_coupon_store）。
 *       ★必须有 scope 这个显式字段：否则只能靠"关联表为空"反推"这是通用券"，
 *       将来分不清是"通用券"还是"配漏了范围"。</li>
 * </ol>
 */
@Data
@TableName("t_coupon")
public class Coupon {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("title")
    private String title;

    /** SECKILL 秒杀券（限库存 + 限单人领取）/ NORMAL 普通券（不限库存、默认不限领） */
    @TableField("coupon_type")
    private String couponType;

    /** 使用门槛：满 X 可用，0 = 无门槛 */
    @TableField("threshold")
    private BigDecimal threshold;

    /** 抵扣金额（只做满减券，不做折扣券） */
    @TableField("discount")
    private BigDecimal discount;

    /** 发放总量；★NORMAL 可为 null 表示不限 */
    @TableField("total")
    private Integer total;

    /** 剩余可领；★NORMAL 为 null，SECKILL 必须非空（Redis 库存的来源） */
    @TableField("remain")
    private Integer remain;

    /**
     * 每人限领张数。★<b>0 = 不限</b>，不用 null 表达"不限"。
     * <p>理由：用 0 时代码是一句 {@code if (limit > 0 && got >= limit)}；
     * 用 null 就要写成 {@code if (limit != null && got >= limit)}，
     * 而"忘了配"和"故意设为不限"在 null 下长得一模一样。</p>
     */
    @TableField("limit_per_user")
    private Integer limitPerUser;

    // ---------- 第一层时间：能不能领 ----------

    @TableField("receive_start_time")
    private LocalDateTime receiveStartTime;

    /** ★只管领取截止，不再兼职"使用截止"（旧库一列两用是隐患） */
    @TableField("receive_end_time")
    private LocalDateTime receiveEndTime;

    // ---------- 第二层时间：领到以后什么时候能用 ----------

    /** FOREVER 长期有效 / DAYS 领后 N 天 / RANGE 固定窗口。语义同 t_course_store.valid_type */
    @TableField("valid_type")
    private String validType;

    @TableField("valid_days")
    private Integer validDays;

    @TableField("use_start_time")
    private LocalDateTime useStartTime;

    @TableField("use_end_time")
    private LocalDateTime useEndTime;

    // ---------- 可用范围 ----------

    /** 0 全场通用 / 1 限指定课程 / 2 限指定门店 */
    @TableField("scope")
    private Integer scope;

    /** 补充说明文案。★规则明细由策略按类型生成（D21），不手写在这里，免得两处不一致 */
    @TableField("description")
    private String description;

    /** 1 上架 / 0 下架 / 2 作废 */
    @TableField("status")
    private Integer status;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

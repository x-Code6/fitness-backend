package com.yuedong.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权益凭证实体，对应表 yd_order.t_entitlement —— <b>买到的"可以去上课的资格"</b>。
 *
 * <p>v2 把旧模型里"一张订单就是一节预约"拆成两条：{@link Booking} 只记钱，
 * 本表记权益。用户付完款拿到 N 张凭证（N = 购买份数，★多份不合并，见 D4），
 * 之后每次预约用掉一张凭证上的一次。</p>
 *
 * <p>★两个 NULL 语义是这张表最容易写错的地方，判断条件必须成对出现：</p>
 * <ul>
 *   <li>{@code totalTimes == null} ⇒ 不限次（UNLIMITED）。次数校验必须写成
 *       {@code total_times IS NULL OR used_times < total_times}，
 *       漏掉前半句会把不限次课当成 0 次卡，用户永远约不了；</li>
 *   <li>{@code validUntil == null} ⇒ 永不过期（FOREVER）。过期扫描 SQL 必须带
 *       {@code valid_until IS NOT NULL}，漏掉后半句会把常年课集体误判过期并退款。</li>
 * </ul>
 */
@Data
@TableName("t_entitlement")
public class Entitlement {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归谁（跨库 → yd_user）。所有查询都必须带上它，否则就成了"猜到 id 就能用别人的凭证" */
    @TableField("user_id")
    private Long userId;

    /** 出票订单（跨库关联用订单号字符串，沿用 t_user_coupon.order_no 的惯例） */
    @TableField("order_no")
    private String orderNo;

    /** 哪门课（跨库 → yd_course） */
    @TableField("course_id")
    private Long courseId;

    /** ★只能在购买门店约教练——物理位置约束，不是快照美化 */
    @TableField("store_id")
    private Long storeId;

    @TableField("course_name")
    private String courseName;

    @TableField("store_name")
    private String storeName;

    /** 快照：一节课占几个整点格子 */
    @TableField("lesson_hours")
    private Integer lessonHours;

    /** 快照：TIMES / UNLIMITED。管理员改了课程售卖形态不能追溯改变已售凭证 */
    @TableField("sale_type")
    private String saleType;

    /** TIMES = 这张凭证给几次；UNLIMITED = null（不限次）。★见类注释的两个 NULL 语义 */
    @TableField("total_times")
    private Integer totalTimes;

    /** 已消费次数。扣次时机 = 预约到点后由定时任务 +1，取消预约不扣 */
    @TableField("used_times")
    private Integer usedTimes;

    /** 使用截止；★null = 永不过期。由 t_course_store 的 valid_type 三选一算出 */
    @TableField("valid_until")
    private LocalDateTime validUntil;

    /** 凭证轴状态，取值见 {@link com.yuedong.order.constant.EntitlementStatus} */
    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 还剩几次。<b>不映射数据库列</b>（列只有 totalTimes/usedTimes），
     * 由服务层算好塞进 VO，避免"剩余次数"这种派生值在库里和代码里各存一份、对不上。
     */
    @TableField(exist = false)
    private Integer remainTimes;

    /**
     * 这张凭证现在能不能拿去预约。★同样不是数据库列，是给前端 actions 用的判定结果。
     * <p>三个条件缺一不可：状态 ACTIVE、有剩余次数（或不限次）、未到有效期
     * （或永久）。任一漏掉都会做出"按钮能点、提交必失败"的页面。</p>
     */
    @TableField(exist = false)
    private Boolean bookable;
}

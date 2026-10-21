package com.yuedong.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 购买订单实体（PO），对应表 yd_order.t_booking。
 *
 * <p>★v2 里这张表只负责<b>钱</b>，不再带任何"什么时候上课"的语义。删掉的列与原因：</p>
 * <ul>
 *   <li>{@code schedule_id} / {@code book_time}：场次表已废弃；旧代码把
 *       {@code t_course.next_time}（"预约制"这类文案）直接塞进 book_time，
 *       于是秒杀单显示的上课时间是句文案——那是模型混用的必然结果，不是笔误；</li>
 *   <li>{@code coach}：教练在预约那一刻才确定，购买时根本不知道；</li>
 *   <li>{@code verify_code} / {@code verified_at}：核销的对象是<b>预约</b>不是订单，
 *       移到 {@code t_reservation}。</li>
 * </ul>
 *
 * <p>{@code courseName}/{@code storeName}/{@code lessonHours}/{@code saleType}/
 * {@code timesGranted} 都是<b>快照</b>：课程跨库且管理员随时会改配置，
 * 已售出的订单必须保持成交时的样子（判断准则见 docs 第一部分讲解㉒）。</p>
 */
@Data
@TableName("t_booking")
public class Booking {

    /** 主键（内部用，对外一律用 orderNo） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对外订单号 */
    @TableField("order_no")
    private String orderNo;


    @TableField("user_id")
    private Long userId;

    /** 买的哪门课（跨库 → yd_course） */
    @TableField("course_id")
    private Long courseId;

    /** 快照：SALE / CONTENT。只有 SALE 可购买，这列用于事后核对与展示分流 */
    @TableField("course_kind")
    private String courseKind;

    /** 在哪家店买的。★凭证认店，之后只能在该店约教练 */
    @TableField("store_id")
    private Long storeId;

    /** 秒杀活动 id；null = 普通购买 */
    @TableField("activity_id")
    private Long activityId;

    @TableField("course_name")
    private String courseName;

    @TableField("store_name")
    private String storeName;

    /** 快照：一节课几小时，详情页显示"2 小时/节" */
    @TableField("lesson_hours")
    private Integer lessonHours;

    /** 快照：TIMES / UNLIMITED */
    @TableField("sale_type")
    private String saleType;

    /** 快照：这一单给几张凭证（TIMES 才有值） */
    @TableField("times_granted")
    private Integer timesGranted;

    /** 买了几份，1 份 = 1 张凭证 */
    @TableField("quantity")
    private Integer quantity;

    /** 原价小计 = 单价 × 数量。秒杀单靠它和 payAmount 做划线价对比 */
    @TableField("original_amount")
    private BigDecimal originalAmount;

    /** 优惠金额 */
    @TableField("discount_amount")
    private BigDecimal discountAmount;

    /** 实付金额 = 原价小计 - 优惠，最低 0 */
    @TableField("pay_amount")
    private BigDecimal payAmount;

    /** 使用的持券记录 id（跨库 → yd_course.t_user_coupon.id） */
    @TableField("coupon_record_id")
    private Long couponRecordId;

    /** 券名快照，详情页要显示"已用新人无门槛券 -¥10" */
    @TableField("coupon_title")
    private String couponTitle;

    /** 订单轴状态，取值见 {@link com.yuedong.order.constant.BookingStatus} */
    @TableField("status")
    private String status;

    @TableField("cancel_reason")
    private String cancelReason;

    /** 1 已发生退款（可能是部分退款）。★本项目退款是写死的，不调支付网关 */
    @TableField("refunded")
    private Integer refunded;

    /**
     * 累计已退款金额。
     * <p>★部分退款时订单仍是 PAID，只有累计退款额达到 {@code pay_amount} 才置 REFUNDED。
     * 不能再用“任意一张凭证退款就整单 REFUNDED”的旧逻辑——那会让上过课的用户也拿全款。</p>
     */
    @TableField("refund_amount")
    private BigDecimal refundAmount;

    @TableField("refund_at")
    private LocalDateTime refundAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("pay_time")
    private LocalDateTime payTime;

    @TableField("canceled_at")
    private LocalDateTime canceledAt;
}

package com.yuedong.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预约订单实体（PO），对应表 yd_order.t_booking。
 *
 * <p>订单状态机：</p>
 * <pre>
 *   UNPAID(待支付) ──pay──► BOOKED(已预约) ──上课完成──► FINISHED(已完成)
 *        │  ▲
 *        └──┴── cancel / 超时未支付 ──► CANCELED(已取消，释放名额)
 * </pre>
 * <p>所有状态流转都用 CAS 式 UPDATE（WHERE 带当前状态）保证并发安全。</p>
 */
@Data
@TableName("t_booking")
public class Booking {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对外订单号（B + 时间戳 + 随机数，前端展示与操作都用它） */
    @TableField("order_no")
    private String orderNo;

    /** 下单用户 id */
    @TableField("user_id")
    private Long userId;

    /** 课程 id */
    @TableField("course_id")
    private Long courseId;

    /** 秒杀活动 id（普通预约为 null） */
    @TableField("activity_id")
    private Long activityId;

    /** 场次 id（预约的具体上课场次） */
    @TableField("schedule_id")
    private Long scheduleId;

    /** 使用的用户持券记录 id（未用券为 null） */
    @TableField("coupon_record_id")
    private Long couponRecordId;

    /** 优惠金额 */
    @TableField("discount_amount")
    private BigDecimal discountAmount;

    /** 核销码（支付成功后生成，到店出示核销） */
    @TableField("verify_code")
    private String verifyCode;

    /** 核销时间（管理端核销后写入） */
    @TableField("verified_at")
    private LocalDateTime verifiedAt;

    /** 课程名快照（下单时冗余，防课程改名影响历史订单展示） */
    @TableField("course_name")
    private String courseName;

    /** 教练名快照 */
    @TableField("coach")
    private String coach;

    /** 上课时间文案（如"今天 20:30"） */
    @TableField("book_time")
    private String bookTime;

    /** 门店名快照 */
    @TableField("store_name")
    private String storeName;

    /** 实付价（秒杀单为秒杀价） */
    @TableField("price")
    private BigDecimal price;

    /** UNPAID/BOOKED/FINISHED/CANCELED */
    @TableField("status")
    private String status;

    /** 取消原因（超时未支付/用户取消） */
    @TableField("cancel_reason")
    private String cancelReason;

    /** 下单时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 支付时间 */
    @TableField("pay_time")
    private LocalDateTime payTime;
}

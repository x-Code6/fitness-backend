package com.yuedong.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 私教预约单实体，对应表 yd_order.t_reservation —— <b>时段轴</b>。
 *
 * <p>一张预约 = 用掉某张凭证的一次，占住某位教练的连续 {@code lessonHours} 个整点格子。
 * 它与 {@link Booking}（钱）、{@link Entitlement}（权益）是三张表三条状态机，
 * "已过期"不是订单状态、"待上课"不是凭证状态。</p>
 *
 * <p>★{@code cancelDeadline} 是<b>入库时算好存下来的列</b>，不是查询时算的：
 * 取消动作只需要一句带 {@code cancel_deadline > NOW()} 的 CAS，
 * Java 里不出现"取当前时间减一小时"这种逻辑，也就不依赖应用服务器时钟与数据库时钟一致。
 * 这是本项目里"把规则固化成数据而不是固化成代码"的一处例子。</p>
 */
@Data
@TableName("t_reservation")
public class Reservation {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对外预约单号（与订单号同一套生成规则，前缀 R 区分） */
    @TableField("reservation_no")
    private String reservationNo;

    @TableField("user_id")
    private Long userId;

    /** 用哪张凭证换的这次预约（同库引用 t_entitlement.id） */
    @TableField("entitlement_id")
    private Long entitlementId;

    /** 冗余凭证所属订单号：详情页与评价校验要直接知道来自哪一单，省一次查询 */
    @TableField("order_no")
    private String orderNo;

    @TableField("course_id")
    private Long courseId;

    @TableField("store_id")
    private Long storeId;

    /** 约了哪位教练（跨库 → yd_course.t_coach） */
    @TableField("coach_id")
    private Long coachId;

    /** ★教练名快照：教练改名或离职后，历史预约单仍显示当时的名字 */
    @TableField("coach_name")
    private String coachName;

    @TableField("course_name")
    private String courseName;

    @TableField("store_name")
    private String storeName;

    /** 本次占用几个整点格子（= 课程的 lessonHours） */
    @TableField("lesson_hours")
    private Integer lessonHours;

    /** ★整点起始，如 2026-10-14 18:00:00。非整点属于脏数据，唯一索引拦不住，入库前由业务代码校验 */
    @TableField("start_time")
    private LocalDateTime startTime;

    /** = start_time + lessonHours 小时。★到点扣次任务判的就是这一列（下课才算消费完） */
    @TableField("end_time")
    private LocalDateTime endTime;

    /** 时段轴状态，取值见 {@link com.yuedong.order.constant.ReservationStatus} */
    @TableField("status")
    private String status;

    /** = start_time - 1 小时。取消只看这一列做 CAS */
    @TableField("cancel_deadline")
    private LocalDateTime cancelDeadline;

    /** 到店核销码（10 位数字，唯一索引）。★v2 里核销只登记到店，不决定是否扣次 */
    @TableField("verify_code")
    private String verifyCode;

    @TableField("verified_at")
    private LocalDateTime verifiedAt;

    /** 到点置 FINISHED 的时刻 = 消费时刻 */
    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

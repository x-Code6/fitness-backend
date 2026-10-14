package com.yuedong.adminserver.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端预约行（订单服务的预约数据 + 会员昵称 + 两个展示用的时间串）。
 *
 * <p>★{@code dateText} / {@code timeText} 是<b>纯展示字段</b>，库里没有、订单服务也不算：
 * 表格里"10-14"和"18:00-20:00"是两列，让前端各切一次字符串会分成四份口径
 * （管理端、H5、导出、以后新页面各切一遍）。后端算一次给出去，正是 BFF 该做的活。</p>
 *
 * <p>{@code startTime} 等原始时间仍然保留：排序、判断"是否已过期"要拿真值，
 * <b>展示串永远不能替代原始值</b>。</p>
 */
@Data
@Builder
public class ReservationAdminVO {

    private Long id;

    private String reservationNo;

    private Long userId;

    /** 会员昵称（admin-server 补） */
    private String nickname;

    /** 用哪张凭证换的（订单详情要按它归组） */
    private Long entitlementId;

    private String orderNo;

    private String courseName;

    private String storeName;

    private Long coachId;

    private String coachName;

    private Integer lessonHours;

    /** 日期列："10-14" */
    private String dateText;

    /** 时间列："18:00-20:00"（跨天的极端情况仍以起止时间显示，不额外造"次日"文案） */
    private String timeText;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime endTime;

    /** BOOKED / FINISHED / CANCELED */
    private String status;

    private String statusText;

    /** 到店核销码 */
    private String verifyCode;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime verifiedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime finishedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime cancelDeadline;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime createdAt;
}

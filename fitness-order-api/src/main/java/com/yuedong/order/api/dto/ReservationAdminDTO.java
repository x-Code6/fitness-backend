package com.yuedong.order.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端预约分页行（时段轴 {@code t_reservation} 的一行）。
 *
 * <p>★教练名、课程名、门店名都是<b>下单时的快照列</b>，不是 JOIN 出来的：
 * 教练改名、离职、门店换名，历史预约单上显示的必须是当时的那一个。
 * 这也让"删教练"变成安全操作——删完之后这一行的 coachName 还在。</p>
 *
 * <p>{@code verifiedAt} 与 {@code finishedAt} 要分开看：前者只代表"人到店了"，
 * 后者才是"课上完、凭证扣了一次"（D5：下课才算消费）。
 * 两者混成一个字段就会做出"核销即扣次"，那正是 v2 明确取消的行为。</p>
 */
@Data
@Builder
public class ReservationAdminDTO {

    private Long id;

    /** 对外预约单号 */
    private String reservationNo;

    /** 会员 id（admin-server 补昵称） */
    private Long userId;

    /** 用哪张凭证换的 */
    private Long entitlementId;

    /** 来自哪一单 */
    private String orderNo;

    private Long courseId;

    private String courseName;

    private Long storeId;

    private String storeName;

    private Long coachId;

    /** 教练名快照 */
    private String coachName;

    /** 占用几个整点格子 */
    private Integer lessonHours;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime startTime;

    /** = start + lessonHours 小时。★到点扣次任务判的就是这一列 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime endTime;

    /** BOOKED / FINISHED / CANCELED */
    private String status;

    private String statusText;

    /** 到店核销码（工作台照着它核对） */
    private String verifyCode;

    /** 到店时间；null = 还没核销 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime verifiedAt;

    /** 结账时间（到点置 FINISHED 的时刻）；null = 还没上完 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime finishedAt;

    /** 取消截止 = start - 1 小时；★这一列同时是"还能不能取消"的判据 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime cancelDeadline;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime createdAt;
}

package com.yuedong.adminserver.converter;

import com.yuedong.order.api.dto.EntitlementAdminDTO;
import com.yuedong.order.api.dto.ReservationAdminDTO;
import com.yuedong.adminserver.model.vo.EntitlementAdminVO;
import com.yuedong.adminserver.model.vo.ReservationAdminVO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 订单侧 DTO → 管理端 VO 的转换（凭证、预约两处共用）。
 *
 * <p>★为什么不写两份在各 controller 里：凭证分页、预约分页、订单详情
 * 三个地方都要转同一对类型，抄两遍的话"昵称取不到显示什么"这种小决定就会分叉。
 * 转换本身没有业务规则，所以它是一个<b>组件</b>而不是一个 service 接口
 * ——为一个纯映射再开接口 + 实现，只是把八个字变成两个文件。</p>
 */
@Component
public class AdminVoConverter {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter HOUR_MIN_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * @param names  userId → 昵称（由 MemberNameLoader 整页取好；查不到就是 null）
     */
    public ReservationAdminVO toVO(ReservationAdminDTO row, Map<Long, String> names) {
        return ReservationAdminVO.builder()
                .id(row.getId())
                .reservationNo(row.getReservationNo())
                .userId(row.getUserId())
                .nickname(names.get(row.getUserId()))
                .entitlementId(row.getEntitlementId())
                .orderNo(row.getOrderNo())
                .courseName(row.getCourseName())
                .storeName(row.getStoreName())
                .coachId(row.getCoachId())
                .coachName(row.getCoachName())
                .lessonHours(row.getLessonHours())
                .dateText(dateText(row.getStartTime()))
                .timeText(timeText(row.getStartTime(), row.getEndTime()))
                .startTime(row.getStartTime())
                .endTime(row.getEndTime())
                .status(row.getStatus())
                .statusText(row.getStatusText())
                .verifyCode(row.getVerifyCode())
                .verifiedAt(row.getVerifiedAt())
                .finishedAt(row.getFinishedAt())
                .cancelDeadline(row.getCancelDeadline())
                .createdAt(row.getCreatedAt())
                .build();
    }

    /** 凭证行。{@code reservations} 由调用方在详情页自己挂（分页时保持 null） */
    public EntitlementAdminVO toVO(EntitlementAdminDTO row, Map<Long, String> names) {
        return EntitlementAdminVO.builder()
                .id(row.getId())
                .userId(row.getUserId())
                .nickname(names.get(row.getUserId()))
                .orderNo(row.getOrderNo())
                .courseId(row.getCourseId())
                .courseName(row.getCourseName())
                .storeId(row.getStoreId())
                .storeName(row.getStoreName())
                .lessonHours(row.getLessonHours())
                .saleType(row.getSaleType())
                .totalTimes(row.getTotalTimes())
                .usedTimes(row.getUsedTimes())
                .paidAmount(row.getPaidAmount())
                .refundAmount(row.getRefundAmount())
                .refundedAt(row.getRefundedAt())
                .remainTimes(row.getRemainTimes())
                .validUntil(row.getValidUntil())
                .validUntilText(row.getValidUntilText())
                .status(row.getStatus())
                .statusText(row.getStatusText())
                .reservationCount(row.getReservationCount())
                .createdAt(row.getCreatedAt())
                .build();
    }

    private String dateText(LocalDateTime start) {
        return start == null ? "" : start.format(DATE_FMT);
    }

    /** 起止任一为空就不拼半个串：列表上出现"18:00-"这种残串会被当成数据坏了 */
    private String timeText(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return "";
        }
        return start.format(HOUR_MIN_FMT) + "-" + end.format(HOUR_MIN_FMT);
    }
}

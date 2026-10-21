package com.yuedong.order.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 管理端凭证分页行（权益轴 {@code t_entitlement} 的一行 + 算出来的两个值）。
 *
 * <p>★为什么不复用 C 端的 {@code EntitlementVO} 的字段形状：
 * C 端那个 VO 里有 {@code canBook}（还要判门店营业时间、教练可约，代价高），
 * 管理端根本不看它；反过来管理端要精确的 {@code createdAt}、要按 userId 筛，
 * C 端 VO 里没有 userId（它天然只服务"当前登录的人"）。
 * <b>两个端口共用一个 VO 的下场，就是为了另一边加字段把展示层搞脏</b>——
 * 这条在 course 域的 CoachVO/CoachAdminDTO 上也写过一次。</p>
 *
 * <p>{@code remainTimes} 与 {@code validUntilText} 都是<b>读时算</b>的：
 * 库里只有 total/used 和 valid_until，剩余次数与"长期有效"这类文案一旦落列就成了第二份真相。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntitlementAdminDTO {

    private Long id;

    /** 归属会员（admin-server 拿它去 user 服务批量补昵称） */
    private Long userId;

    /** 来自哪一单 */
    private String orderNo;

    private Long courseId;

    /** 课程名快照 */
    private String courseName;

    private Long storeId;

    /** 门店名快照 */
    private String storeName;

    /** 一次消耗几课时 */
    private Integer lessonHours;

    /** TIMES / UNLIMITED */
    private String saleType;

    private Integer totalTimes;

    private Integer usedTimes;

    /** 出票时按份数分摊到的实付金额 */
    private BigDecimal paidAmount;

    /** 已退款金额；0 = 没退过 */
    private BigDecimal refundAmount;

    /** 退款时间；null = 没退过 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime refundedAt;

    /** 剩余次数（UNLIMITED 时前端显示"不限"，这个值只是形式上的） */
    private Integer remainTimes;

    /** 有效期截止；<b>null = 长期有效</b>（FOREVER）。★所有过期判断都要带 IS NOT NULL 就是为它 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime validUntil;

    /** 有效期文案：长期有效 / 2026-12-31 前 */
    private String validUntilText;

    /**
     * 这张凭证被约过几次（已取消的不算）。
     * <p>★由<b>订单服务</b>算而不是 admin-server：预约表就在 yd_order，
     * 一次 GROUP BY 就出整页；换到 BFF 去算就得为这个数多发一次跨服务请求。</p>
     */
    private Integer reservationCount;

    /** ACTIVE / USED_UP / EXPIRED */
    private String status;

    private String statusText;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime createdAt;
}

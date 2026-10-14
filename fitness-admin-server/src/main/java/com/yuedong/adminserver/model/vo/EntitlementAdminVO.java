package com.yuedong.adminserver.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端凭证行（订单服务的凭证数据 + 会员昵称，两处来源拼成一行）。
 *
 * <p>★为什么不把 order 那边的 {@code EntitlementAdminDTO} 直接透传出去：
 * 列表要显示"是谁的凭证"，而昵称在 yd_user，订单服务查不到。
 * BFF 存在的意义就是把这一列补上，<b>而不是让前端拿到 userId 自己再查一次</b>
 * （用户侧接口也不给别人查昵称，前端只会拿到 403）。</p>
 *
 * <p>{@code reservations} <b>只有订单详情里才填</b>，凭证分页时为 null：
 * 分页列表不需要展开每一张的预约（一屏十几行、每行再挂一串，页面会没法看）；
 * 详情页要的是"这张凭证上过哪几节课"，所以挂上去。
 * 用同一个 VO 而不是再建一个 DetailVO，是因为字段完全同名同义，
 * 多一个类只是多一次转换。</p>
 */
@Data
@Builder
public class EntitlementAdminVO {

    private Long id;

    private Long userId;

    /** 会员昵称（admin-server 补；用户服务不可用时为 null，前端显示"—"） */
    private String nickname;

    private String orderNo;

    private Long courseId;

    private String courseName;

    private Long storeId;

    private String storeName;

    private Integer lessonHours;

    /** TIMES / UNLIMITED */
    private String saleType;

    private Integer totalTimes;

    private Integer usedTimes;

    /** 剩余次数；UNLIMITED 形态为 null（显示"不限"） */
    private Integer remainTimes;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime validUntil;

    /** 长期有效 / xxxx-xx-xx 到期 */
    private String validUntilText;

    private String status;

    private String statusText;

    /** 已约次数（未取消的预约张数） */
    private Integer reservationCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime createdAt;

    /** ★仅订单详情填充；凭证分页为 null */
    private List<ReservationAdminVO> reservations;
}

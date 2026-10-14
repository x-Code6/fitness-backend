package com.yuedong.order.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单 DTO（跨服务给 fitness-admin-server 的管理端订单列表 / 详情）。
 *
 * <p>★v2 变化：{@code id}（其实是订单号，名字误导）改成 {@code orderNo}；
 * 删掉 {@code coach} / {@code time} / {@code scheduleTime} / {@code verifyCode}
 * 四个字段——购买阶段根本没有教练和时间，核销码属于预约而不是订单。</p>
 *
 * <p>金额同时给 {@code originalAmount}（原价小计）、{@code discountAmount}（优惠）、
 * {@code payAmount}（实付）三个值：管理端要核对"券有没有算错"，只给实付查不出问题。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingDTO {

    /** 对外订单号 */
    private String orderNo;

    /** 购物车合并结算的组号；null = 单买 */
    private String parentNo;

    /** 下单人。★给管理端用来批量聚合会员昵称 */
    private Long userId;

    private Long courseId;

    private String courseName;

    /** SALE / CONTENT */
    private String courseKind;

    private Long storeId;

    private String storeName;

    private Integer lessonHours;

    /** TIMES / UNLIMITED */
    private String saleType;

    /** 这一单给几次 */
    private Integer timesGranted;

    private Integer quantity;

    private BigDecimal originalAmount;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    /** 券名快照 */
    private String couponTitle;

    private String status;

    /** 中文文案，管理端直接渲染 */
    private String statusText;

    private Boolean fromSeckill;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime payTime;

    private String cancelReason;
}

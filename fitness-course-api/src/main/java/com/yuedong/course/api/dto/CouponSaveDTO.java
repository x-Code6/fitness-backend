package com.yuedong.course.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理端建券/改券入参（★v2 表单联动见 docs 4.3.4）。
 *
 * <p>三条联动规则是数据正确性的第一道防线（后端校验仍然都写，但用户面对的是表单）：</p>
 * <ol>
 *   <li>{@code couponType=SECKILL} 才显示 {@code total/remain/limitPerUser}；
 *       NORMAL 时这三项隐藏并置为不限；</li>
 *   <li>{@code validType=DAYS} 只显示 {@code validDays}、{@code RANGE} 只显示
 *       {@code useStartTime/useEndTime}、{@code FOREVER} 两个都不显示。
 *       ★三个都显示让人随便填，后端读哪个都是隐患——隐式规则一定会被误读；</li>
 *   <li>{@code scope=1} 才显示课程多选、{@code scope=2} 才显示门店多选，
 *       所以 {@code courseIds}/{@code storeIds} 允许为 null（= 不适用），
 *       但<b>传了 scope=1 却不给 courseIds 要报错</b>——那是配漏了而不是通用券。</li>
 * </ol>
 */
@Data
public class CouponSaveDTO {

    /** null = 新建 */
    private Long id;

    @NotBlank(message = "请填写券名")
    private String title;

    /** SECKILL / NORMAL */
    @NotBlank(message = "请选择券类型")
    private String couponType;

    @NotNull(message = "请填写使用门槛")
    @Min(value = 0, message = "门槛不能为负")
    private BigDecimal threshold;

    @NotNull(message = "请填写抵扣金额")
    private BigDecimal discount;

    /** 发放总量；NORMAL 可空 = 不限 */
    private Integer total;

    /** 每人限领张数；★0 = 不限 */
    private Integer limitPerUser;

    /** 可领开始，格式 yyyy-MM-dd HH:mm */
    @NotBlank(message = "请选择领取开始时间")
    private String receiveStartTime;

    /** 可领截止，格式 yyyy-MM-dd HH:mm */
    @NotBlank(message = "请选择领取截止时间")
    private String receiveEndTime;

    /** FOREVER / DAYS / RANGE */
    @NotBlank(message = "请选择有效期类型")
    private String validType;

    /** validType=DAYS 时必填 */
    private Integer validDays;

    /** validType=RANGE 时必填，格式 yyyy-MM-dd HH:mm */
    private String useStartTime;

    /** validType=RANGE 时必填 */
    private String useEndTime;

    /** 0 全场 / 1 限课程 / 2 限门店 */
    private Integer scope;

    private List<Long> courseIds;

    private List<Long> storeIds;

    private String description;

    /** 1 上架 / 0 下架。★编辑时必须带上，否则编辑一次就把上架中的券悄悄下架了 */
    private Integer status;

    // ===== 活动页（Banner）=====
    private Boolean bannerEnabled;

    private String bannerImageUrl;

    private String bannerTitle;

    private String bannerSubtitle;

    private Integer bannerSort;

    /** 可选覆盖；空则默认跟随领取窗口 */
    private String bannerStartTime;

    private String bannerEndTime;
}

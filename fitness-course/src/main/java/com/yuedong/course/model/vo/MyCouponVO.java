package com.yuedong.course.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * "我的券"里的一张持券（★字段名对齐前端 Mock 的 {@code myCouponRecords} 扩展）。
 *
 * <p>★{@code recordId} 而不是 {@code couponId} 是下单要用的一张券的标识：
 * 模板 id 一个人可以有多张，核销必须指定"哪一张"。
 * 这是"模板-实例"分离必须付出的表达成本，也是 {@code t_user_coupon.id} 存在的理由。</p>
 */
@Data
@Builder
public class MyCouponVO {

    /** 持券记录 id（t_user_coupon.id）。★下单传的是它 */
    private Long recordId;

    private Long couponId;

    private String title;

    /** SECKILL / NORMAL */
    private String couponType;

    private String typeText;

    private BigDecimal threshold;

    private BigDecimal discount;

    /** UNUSED / USED / EXPIRED */
    private String status;

    private String statusText;

    /** 领取时间（格式化字符串） */
    private String receivedText;

    /** 使用截止；★null = 长期有效（FOREVER），前端显示"长期有效"而不是空 */
    private String validUntil;

    private String validText;

    /** 核销在哪张订单上（未用为 null） */
    private String usedOrderNo;

    /**
     * 这张券现在能不能拿去下单 = 未使用 且 未过期 且 券模板还上架。
     * <p>★过期判断在<b>领的时候就把 validUntil 算好存进持券记录了吗？没有</b>：
     * 本项目只在读取时用模板的 valid_type 现算，理由是不想为一个可重建的派生值加一列，
     * 代价是"管理员改了券的有效期会影响已发券"，这条限制要心里有数。</p>
     */
    private Boolean usable;
}

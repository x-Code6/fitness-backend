package com.yuedong.order.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 购物车里的一条（★字段名对齐前端 Mock 的 {@code cartItemVO}）。
 *
 * <p>{@code selected} 给布尔而不是 0/1：勾选框要的是布尔，
 * 而"给数值还是给布尔"这件事上一次留了隐患——前端 {@code selected === 1}
 * 与 {@code !!selected} 两种写法都能过，改协议时必炸一处。
 * <b>对外协议里布尔就用布尔，数据库里 0/1 是存储细节。</b></p>
 */
@Data
@Builder
public class CartItemVO {

    /** 条目 id。★改份数/勾选/删除都回传它 */
    private Long itemId;

    private Long courseId;

    private String courseName;

    private String courseType;

    /** 卡片渐变底色下标（来自课程快照，购物车条目也要能画出跟列表一样的卡片） */
    private String coverColor;

    private Integer lessonHours;

    /** TIMES / UNLIMITED */
    private String saleType;

    private BigDecimal unitPrice;

    private String unitPriceText;

    private Long storeId;

    /** ★凭证认店，这行字在购物车里必须显眼：用户会以为买了就能 anywhere 约 */
    private String storeName;

    private Integer quantity;

    private Boolean selected;

    /** 小计 = 单价 × 份数 */
    private BigDecimal amount;

    private String amountText;

    /**
     * 这条现在还能不能买（课程在售 + 该店在可售窗口内）。
     * <p>★下架的条目<b>不隐藏而是标灰</b>：悄悄删掉用户挑好的东西，
     * 比显示"已停售"更让人恼火，也更难解释。</p>
     */
    private Boolean onSale;

    private String offSaleText;

    /** 有效期说明："支付后 30 天内可用" / "长期有效" / "可用至 2026-12-01" */
    private String validText;
}

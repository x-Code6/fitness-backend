package com.yuedong.order.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车整页（"我的购物车"唯一的一个读接口）。
 *
 * <p>★合计由后端算：勾选状态、单价、份数三者相乘再求和，前端算一遍、
 * 结算页再算一遍，两处不一致就会出现"购物车写 ¥268、收银台要付 ¥336"。
 * <b>凡是"多处都要显示的合计"，都只有一个生产者。</b></p>
 */
@Data
@Builder
public class CartListVO {

    private List<CartItemVO> items;

    /** 已勾选条目的合计（下架未勾选的不进合计） */
    private BigDecimal totalAmount;

    private String totalAmountText;

    /** 已勾选的条目数（结算按钮上写"结算(3)"用） */
    private Integer selectedCount;

    /**
     * 已勾选条目<b>合计份数</b>，TabBar 角标与"共 N 件"用。
     * <p>和 selectedCount 分开：一个是条目数、一个是份数，"2 条 / 3 份"是常见的。</p>
     */
    private Integer totalQuantity;

    /** 空车时的引导文案（前端不给空列表画一个孤零零的结算按钮） */
    private String emptyText;
}

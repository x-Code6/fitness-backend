package com.yuedong.order.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车结算结果（对应前端 {@code checkoutCart}）。
 *
 * <p>★结算<b>不是"一张大订单"</b>：按 D12 拆成 N 张订单 + 同一个 {@code parentNo}，
 * 支付时一条 {@code UPDATE ... WHERE parent_no=?} 整组流转。
 * 不引入父订单表是刻意的——那会多出一条没人需要的状态机
 * （"父订单已取消而子订单还在待支付"这种状态谁来管？没人管就得承认它可能不一致，
 * 既然要承认，不如一开始就不要它）。</p>
 */
@Data
@Builder
public class CheckoutResultVO {

    /** 组号，P 开头。★支付时要回传它（一次付清一组） */
    private String parentNo;

    /** 生成的订单列表，前端可直接跳"待支付列表" */
    private List<BookingVO> orders;

    /** 本组实付合计 */
    private BigDecimal payAmount;

    private String payAmountText;

    /** 本组共出几张凭证（= 各单份数之和），结算成功提示要用："已生成 3 张凭证" */
    private Integer entitlementTotal;
}

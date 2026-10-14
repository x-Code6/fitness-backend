package com.yuedong.order.model.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 趋势图一行（内部读结果）。
 *
 * <p>{@code date} 直接是 SQL 里 {@code DATE_FORMAT(...,'%m-%d')} 出来的 "10-14"，
 * 因为横轴只要月日；用 LocalDate 接回来再格式化一次是白绕一圈。</p>
 */
@Data
public class TrendRow {

    /** 日期标签 MM-dd */
    private String date;

    /** 当日已支付实付合计 */
    private BigDecimal amount;

    /** 当日下单数（含未支付与已退款） */
    private Integer orderCount;
}

package com.yuedong.order.model.vo;

import lombok.Data;

/**
 * 订单状态分布一行（内部读结果）。
 *
 * <p>{@code status} 是订单轴枚举字符串（UNPAID/PAID/CANCELED/REFUNDED），
 * 转成 DTO 时不翻译文案——看板把枚举直接给前端，颜色与文案的映射留在展示层，
 * 与「后端给原因码不给文案」的同一套约定。</p>
 */
@Data
public class StatusCountRow {

    /** 订单状态枚举值 */
    private String status;

    /** 该状态的订单数 */
    private Integer count;
}

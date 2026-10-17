package com.yuedong.order.model.dto;

import lombok.Data;

/**
 * 支付入参（对应前端 {@code POST /booking/pay}）。
 *
 * <p>★字段名必须和前端发的键一致。这里<b>不能</b>沿用旧的那个 {@code id} 字段——
 * 旧字段名和前端发的键完全不同，接上真实后端后表现是
 * "接口 200、钱没付"，比报错更难发现。</p>
 *
 * <p>以前还有一个 {@code parentNo}（购物车合并支付时一次付清同组 N 张单）。
 * 购物车去掉后一个订单就是一门课，组号没有存在意义了，
 * 连带删掉 {@code markGroupPaid}／{@code cancelGroup} 两条整组 UPDATE。</p>
 */
@Data
public class PayDTO {

    /** 要支付的订单号 */
    private String orderNo;
}

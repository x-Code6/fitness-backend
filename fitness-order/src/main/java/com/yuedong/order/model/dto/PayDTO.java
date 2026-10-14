package com.yuedong.order.model.dto;

import lombok.Data;

/**
 * 支付入参（对应前端 {@code POST /booking/pay}）。
 *
 * <p>★两个字段二选一：{@code orderNo} 单买支付、{@code parentNo} 购物车合并支付。
 * 前端收银台就是这么发的
 * （{@code payBooking(parentNo ? {parentNo} : {orderNo})}），
 * 所以这里<b>不能</b>沿用旧的那个 {@code id} 字段——旧字段名和前端发的键完全不同，
 * 接上真实后端后表现是"接口 200、钱没付"。</p>
 *
 * <p>两个都不传时由服务层报错（不写在注解里，因为"至少传一个"这种跨字段规则
 * 用 Bean Validation 要写自定义校验器，可读性反而更差）。</p>
 */
@Data
public class PayDTO {

    /** 单笔订单号 */
    private String orderNo;

    /** 购物车结算的组号：传它就一次付清同组所有待支付订单 */
    private String parentNo;
}

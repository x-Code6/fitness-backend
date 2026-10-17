package com.yuedong.order.model.dto;

import lombok.Data;

/**
 * 取消订单入参（对应前端 {@code POST /booking/cancel}）。
 * <p>★与 {@link PayDTO} 同样二选一：取消一笔还是取消整组，
 * 用户在购物车结算后是"一组待支付"，只给单笔入口会让他一张张点。</p>
 */
@Data
public class CancelDTO {

    /** 单笔订单号 */
    private String orderNo;

}

package com.yuedong.order.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 秒杀排队结果（前端轮询此结构）。
 */
@Data
@AllArgsConstructor
public class SeckillResultVO {

    /** QUEUEING 排队中 / SUCCESS 抢到了 / FAIL 失败 */
    private String status;

    /** 成功时的订单号（拿去"我的预约"里支付） */
    private String orderNo;

    /** 失败原因 */
    private String reason;
}

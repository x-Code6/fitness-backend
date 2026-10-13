package com.yuedong.order.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 支付请求参数（模拟支付；真实项目此处替换为预支付单创建）。
 */
@Data
public class PayDTO {

    /** 订单号 */
    @NotBlank(message = "订单号不能为空")
    private String id;
}

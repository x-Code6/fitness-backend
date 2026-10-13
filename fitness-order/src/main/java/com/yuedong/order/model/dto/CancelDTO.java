package com.yuedong.order.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 取消预约请求参数。
 */
@Data
public class CancelDTO {

    /** 订单号 */
    @NotBlank(message = "订单号不能为空")
    private String id;
}

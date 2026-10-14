package com.yuedong.order.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 加入购物车（对应前端 {@code POST /cart/add}） */
@Data
public class CartAddDTO {

    @NotNull(message = "请选择课程")
    private Long courseId;

    /** ★必填：凭证认店，购物车条目也必须认店，否则结算时不知道出哪家店的凭证 */
    @NotNull(message = "请选择门店")
    private Long storeId;

    /** 加几份，不传按 1 */
    @Min(value = 1, message = "至少加 1 份")
    @Max(value = 10, message = "单个条目最多 10 份")
    private Integer quantity;
}

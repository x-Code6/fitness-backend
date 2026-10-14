package com.yuedong.order.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除评价入参（对应前端 {@code POST /rating/delete}） */
@Data
public class RatingDeleteDTO {

    @NotNull(message = "缺少评价 id")
    private Long ratingId;
}

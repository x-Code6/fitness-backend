package com.yuedong.order.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 秒杀下单请求参数（点击抢课，立即返回排队号，结果靠轮询）。
 */
@Data
public class SeckillBookDTO {

    /** 秒杀课程 id（服务端按课程找进行中的活动） */
    @NotNull(message = "课程 id 不能为空")
    private Long courseId;
}

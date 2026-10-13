package com.yuedong.course.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端创建/编辑优惠券请求参数。
 */
@Data
public class CouponSaveDTO {

    /** 编辑时传 id */
    private Long id;

    @NotBlank(message = "券名不能为空")
    private String title;

    /** 满 X 元可用 */
    @NotNull(message = "使用门槛不能为空")
    private BigDecimal threshold;

    /** 抵扣金额 */
    @NotNull(message = "抵扣金额不能为空")
    private BigDecimal discount;

    /** 发放总量 */
    @NotNull(message = "总量不能为空")
    private Integer total;

    /** 领取开始时间（yyyy-MM-dd HH:mm:ss） */
    @NotNull(message = "开始时间不能为空")
    private String startTime;

    /** 领取截止时间 */
    @NotNull(message = "截止时间不能为空")
    private String endTime;
}

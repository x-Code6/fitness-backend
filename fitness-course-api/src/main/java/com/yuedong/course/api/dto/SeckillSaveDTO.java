package com.yuedong.course.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端创建秒杀活动请求参数。
 */
@Data
public class SeckillSaveDTO {

    @NotNull(message = "课程不能为空")
    private Long courseId;

    /** 专场门店。★必填：订单与凭证都要认店，没有它这条活动建出来也履约不了 */
    @NotNull(message = "请选择秒杀专场的门店")
    private Long storeId;

    @NotNull(message = "秒杀价不能为空")
    private BigDecimal seckillPrice;

    @NotNull(message = "名额不能为空")
    private Integer total;

    /** 开始时间（yyyy-MM-dd HH:mm:ss） */
    @NotBlank(message = "开始时间不能为空")
    private String startTime;

    /** 结束时间 */
    @NotBlank(message = "结束时间不能为空")
    private String endTime;

    // ===== 活动页（Banner）=====
    private Boolean bannerEnabled;

    private String bannerImageUrl;

    private String bannerTitle;

    private String bannerSubtitle;

    private Integer bannerSort;

    /** 可选覆盖；空则默认跟随秒杀活动起止 */
    private String bannerStartTime;

    private String bannerEndTime;
}

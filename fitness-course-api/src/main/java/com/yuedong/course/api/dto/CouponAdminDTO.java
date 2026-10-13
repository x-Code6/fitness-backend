package com.yuedong.course.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 管理端优惠券分页行 DTO。
 */
@Data
@Builder
public class CouponAdminDTO {

    private Long id;

    private String title;

    /** 使用门槛（元），0=无门槛 */
    private BigDecimal threshold;

    /** 抵扣面额（元） */
    private BigDecimal discount;

    private Integer total;

    private Integer remain;

    /** 每人限领张数 */
    private Integer limitPerUser;

    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")

    private LocalDateTime startTime;

    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")

    private LocalDateTime endTime;

    /** 1=进行中 2=已作废 */
    private Integer status;
}

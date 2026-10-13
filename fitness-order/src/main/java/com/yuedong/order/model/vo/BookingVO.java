package com.yuedong.order.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预约订单视图对象（字段与前端 Mock 完全对齐）。
 */
@Data
@Builder
public class BookingVO {

    /** 对外订单号 */
    private String id;

    private Long courseId;

    private String courseName;

    private String coach;

    /** 上课时间文案 */
    private String time;

    private String storeName;

    private BigDecimal price;

    /** UNPAID/BOOKED/FINISHED/CANCELED */
    private String status;

    private LocalDateTime createdAt;

    /** 是否秒杀单（前端展示"秒杀价"角标） */
    private Boolean fromSeckill;

    /** 核销码（已预约状态展示给用户到店出示） */
    private String verifyCode;

    /** 优惠金额（用券时 > 0） */
    private BigDecimal discountAmount;

    /** 上课场次时间（yyyy-MM-dd HH:mm 起） */
    private String scheduleTime;
}

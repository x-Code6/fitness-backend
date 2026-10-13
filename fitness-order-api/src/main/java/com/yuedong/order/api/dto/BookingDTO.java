package com.yuedong.order.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预约订单 DTO（管理端核销/订单列表等内部接口的跨服务传输对象）。
 */
@Data
@Builder
public class BookingDTO {

    /** 对外订单号 */
    private String id;

    /** 下单用户（管理端聚合会员昵称用） */
    private Long userId;

    private Long courseId;

    private String courseName;

    private String coach;

    /** 上课时间文案 */
    private String time;

    private String storeName;

    private BigDecimal price;

    /** UNPAID/BOOKED/FINISHED/CANCELED */
    private String status;

    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")

    private LocalDateTime createdAt;

    /** 是否秒杀单 */
    private Boolean fromSeckill;

    /** 核销码（已预约状态展示） */
    private String verifyCode;

    /** 优惠金额（用券时 > 0） */
    private BigDecimal discountAmount;

    /** 上课场次时间（yyyy-MM-dd HH:mm 起） */
    private String scheduleTime;
}

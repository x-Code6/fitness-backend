package com.yuedong.adminserver.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单管理页行视图（order 订单数据 + user 会员昵称聚合）。
 */
@Data
@Builder
public class OrderAdminVO {

    /** 对外订单号 */
    private String id;

    /** 会员昵称（user 服务聚合；补齐失败时为 null） */
    private String userName;

    private String courseName;

    private String coach;

    private String storeName;

    private BigDecimal price;

    /** UNPAID/BOOKED/FINISHED/CANCELED */
    private String status;

    /** yyyy-MM-dd HH:mm */
    private String createdAt;
}

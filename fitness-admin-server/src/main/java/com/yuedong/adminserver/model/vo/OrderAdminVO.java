package com.yuedong.adminserver.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单管理页行视图（order 订单数据 + user 会员昵称聚合）。
 *
 * <p>★这是 BFF 存在的唯一理由：订单服务只存 userId 与订单本身，
 * "哪个会员下的单"要跨服务查 user。聚合放在 admin-server 而不是让前端发两次请求，
 * 是为了让前端一个请求拿到一页数据。</p>
 *
 * <p>★v2 字段变化：{@code id} 改成 {@code orderNo}（原名误导，它一直是订单号不是主键）；
 * 删掉 {@code coach}（购买阶段没有教练，教练属于预约）；
 * {@code price} 拆成 {@code originalAmount / discountAmount / payAmount} 三个值——
 * 运营在后台核对"券有没有算错"时，只看实付是查不出问题的。</p>
 */
@Data
@Builder
public class OrderAdminVO {

    /** 对外订单号 */
    private String orderNo;

    /** 会员昵称（user 服务聚合；补齐失败时为 null，前端显示"—"） */
    private String userName;

    private Long userId;

    private String courseName;

    private String storeName;

    /** 一节课几小时，列表显示"2 小时/节" */
    private Integer lessonHours;

    /** TIMES / UNLIMITED */
    private String saleType;

    /** 这一单给几次凭证 */
    private Integer timesGranted;

    private Integer quantity;

    /** 原价小计 */
    private BigDecimal originalAmount;

    /** 优惠金额 */
    private BigDecimal discountAmount;

    /** 实付金额 */
    private BigDecimal payAmount;

    /** 使用的券名；null = 没用券 */
    private String couponTitle;

    /** UNPAID / PAID / CANCELED / REFUNDED */
    private String status;

    /** 中文文案，后端给，避免前端各维护一份状态字典 */
    private String statusText;

    /** 是否秒杀单 */
    private Boolean fromSeckill;

    /** 下单时间 yyyy-MM-dd HH:mm */
    private String createdAt;

    /** 支付时间；未支付为 null */
    private String payTime;
}

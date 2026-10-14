package com.yuedong.order.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建购买订单的入参。
 *
 * <p>★v2 的关键变化：{@code scheduleId} 换成了 {@code storeId}。
 * 旧模型下单时必须选一个场次（下单即预约），新模型是<b>先买课、之后再约教练和时间</b>，
 * 所以下单只需要知道"买哪门课、在哪家店买"，完全不需要时间。</p>
 *
 * <p>{@code quantity}（份数）与购物车留到下一分支再加；
 * 现在一个请求就是一单一份，避免一次改动同时动两个契约。</p>
 */
@Data
public class BookingCreateDTO {

    /** 要买的课程 */
    @NotNull(message = "请选择课程")
    private Long courseId;

    /** 在哪家店买。★必填：决定之后能在哪家店约教练 */
    @NotNull(message = "请选择门店")
    private Long storeId;

    /** 使用的持券记录 id（{@code t_user_coupon.id}），不用券就不传 */
    private Long couponRecordId;
}

package com.yuedong.order.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建购买订单的入参。
 *
 * <p>★v2 的关键变化：{@code scheduleId} 换成了 {@code storeId}。
 * 旧模型下单时必须选一个场次（下单即预约），新模型是<b>先买课、之后再约教练和时间</b>，
 * 所以下单只需要知道"买哪门课、在哪家店买"，完全不需要时间。</p>
 */
@Data
public class BookingCreateDTO {

    /** 要买的课程 */
    @NotNull(message = "请选择课程")
    private Long courseId;

    /** 在哪家店买。★必填：决定之后能在哪家店约教练 */
    @NotNull(message = "请选择门店")
    private Long storeId;

    /**
     * 买几份。1 份 = 1 张凭证（★多份不合并，D4）。不传按 1 份。
     * <p>{@code @Max(10)} 和上限常量是同一个数，两处都要改才生效——
     * 校验注解拦的是"明显非法的输入"（省一次服务层判断），常量拦的是业务出票张数。</p>
     */
    @Min(value = 1, message = "份数至少为 1")
    @Max(value = 10, message = "单次最多购买 10 份")
    private Integer quantity;

    /** 使用的持券记录 id（{@code t_user_coupon.id}），不用券就不传 */
    private Long couponRecordId;
}

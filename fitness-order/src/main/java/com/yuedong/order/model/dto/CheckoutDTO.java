package com.yuedong.order.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 购物车结算入参（对应前端 {@code POST /cart/checkout}）。
 *
 * <p>★<b>券按子单各选一张</b>（已确认口径），所以这里传的不是 {@code itemIds + 一个 couponRecordId}，
 * 而是每个条目自带一张券。理由：合并结算会生成 N 张订单，每张订单各自要满足门槛、
 * 各自受 scope（限课程/限门店）约束——一张券挂到组上是没法判断"能不能用"的。</p>
 *
 * <p>前端旧 Mock 发的是"整组共用一张券（抵在第一张单上）"，
 * 联调时把请求体改成这里的形状即可：{@code items:[{cartItemId, couponRecordId}]}。</p>
 */
@Data
public class CheckoutDTO {

    @NotEmpty(message = "请先勾选要结算的课程")
    @Valid
    private List<Line> items;

    /** 一条购物车条目 + 这条要用的一张券 */
    @Data
    public static class Line {

        /** 购物车条目 id */
        @NotNull(message = "缺少条目 id")
        private Long cartItemId;

        /** 这张子单用的券（t_user_coupon.id），不用券就不传 */
        private Long couponRecordId;
    }
}

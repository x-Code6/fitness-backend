package com.yuedong.course.api.client;

import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.CouponUsableDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * 优惠券契约（课程服务承载优惠券域；订单服务下单用券时 Feign 调用）。
 */
@FeignClient(name = "fitness-course", contextId = "couponClient")
public interface CouponClient {

    /**
     * 校验持券可用性。
     *
     * @param amount   该笔订单原价（判门槛）
     * @param courseId 买的哪门课（判 scope=1），不传则不做课程范围校验
     * @param storeId  在哪家店买（判 scope=2），不传则不做门店范围校验
     * <p>★v2 为什么要把 courseId/storeId 传进来：券有了可用范围之后，
     *   "这张券能不能用"就<b>不再是券自己的属性</b>，而是"券 × 这笔订单"的关系。
     *   只传金额的话，范围校验根本没有输入可做，一张限高新店的券会被用在大学城店。</p>
     */
    @GetMapping("/inner/coupon/info")
    Result<CouponUsableDTO> info(@RequestParam("recordId") Long recordId,
                                 @RequestParam("userId") Long userId,
                                 @RequestParam("amount") BigDecimal amount,
                                 @RequestParam(value = "courseId", required = false) Long courseId,
                                 @RequestParam(value = "storeId", required = false) Long storeId);

    /** 核销用券（UNUSED → USED，绑定订单号） */
    @PostMapping("/inner/coupon/use")
    Result<Boolean> use(@RequestParam("recordId") Long recordId,
                        @RequestParam("userId") Long userId,
                        @RequestParam("orderNo") String orderNo);

    /** 退券（订单取消/超时回退为未使用） */
    @PostMapping("/inner/coupon/refund")
    Result<Boolean> refund(@RequestParam("recordId") Long recordId);
}
